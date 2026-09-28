# ADR-006: Programación de checks sobre PostgreSQL con `SKIP LOCKED`

- **Estado:** Aceptado
- **Fecha:** 2026-09-28
- **Relacionado:** [Motor de monitoreo](../architecture/monitoring-engine.md#3-programación-scheduling) · [ADR-002](ADR-002-postgresql.md) · [ADR-009](ADR-009-redis.md)

## 1. ¿Qué problema existe?

Miles de monitores tienen que ejecutarse cada N segundos (de 30 a 3600), de forma puntual, sin duplicados, sobreviviendo a los reinicios y, en el futuro, con varias instancias del motor.

## 2. ¿Cuáles son los requisitos?

- Cada monitor activo, una vez por intervalo, con un lag p95 < 2 s a la carga objetivo de V1.
- **Cero ejecuciones duplicadas**, incluso con varias instancias.
- Las altas, bajas, pausas y cambios de intervalo tienen efecto sin reiniciar nada.
- Tolerancia a caídas sin locks huérfanos.
- Degradación suave ante sobrecarga (sin colas en memoria que crezcan sin límite).
- Sin infraestructura nueva en V1.

## 3. ¿Qué alternativas tenemos?

1. Una tarea en memoria por monitor (`TaskScheduler`).
2. Quartz con JobStore JDBC en clúster.
3. Un bucle único con ShedLock.
4. JobRunr.
5. **Polling de PostgreSQL con `FOR UPDATE SKIP LOCKED` sobre `next_check_at`.**
6. Cola externa (sorted sets de Redis, colas con retardo en RabbitMQ).

## 4 y 5. Ventajas y desventajas

| Alternativa | Ventajas | Desventajas |
|---|---|---|
| Tareas en memoria | Trivial con una instancia | Estado en memoria a sincronizar con cada cambio. Duplicados con varias instancias. Se pierde la fase al reiniciar |
| Quartz | Maduro, en clúster | Unas 11 tablas propias, un trigger por monitor y un modelo pesado para un intervalo fijo |
| ShedLock | Muy simple | Una sola instancia ejecuta. No escala horizontalmente |
| JobRunr | Buena experiencia, panel | Otra dependencia con almacenamiento propio. Sus reintentos y su semántica no encajan con los checks |
| **`SKIP LOCKED`** | Estado en la fuente de verdad. N instancias sin coordinación. La base de datos hace de cola persistente. Observable con SQL | Una consulta por segundo e instancia. Depende de un buen índice parcial |
| Cola externa | Escala mucho | Infraestructura nueva sin un problema medido. Dos fuentes de verdad que sincronizar |

## 6. ¿Qué elegimos?

Un dispatcher por instancia que, cada segundo, **reclama con `FOR UPDATE SKIP LOCKED`** hasta tantos monitores vencidos como permisos libres tenga el semáforo, **avanza `next_check_at`** en la misma transacción corta (fixed-rate, **sin catch-up**) y entrega los checks al executor. Jitter inicial al crear o reanudar.

## 7. ¿Por qué?

- Cumple todos los requisitos con lo que ya existe (PostgreSQL).
- Resuelve la coordinación entre instancias sin locks distribuidos: esto **elimina uno de los motivos habituales para introducir Redis**.
- La cola es persistente y visible: los vencidos y el lag se consultan con SQL y se exponen como métricas.
- Reclamar solo lo que se puede ejecutar da presión de carga natural.

## 8. ¿Qué costo o complejidad introduce?

- Consulta de claim con CTE que hay que entender y probar (incluido su plan de ejecución).
- Carga constante sobre PostgreSQL: una consulta por segundo e instancia, que con el índice parcial solo toca filas vencidas.
- Relojes sincronizados entre instancias (NTP), porque `:now` sale del reloj de la aplicación.
- Un check reclamado por una instancia que cae se pierde (se ejecuta en el siguiente intervalo). Es aceptable.

## 9. ¿Cómo comprobaremos que funciona?

- Test de concurrencia con dos claimers: 0 duplicados.
- Test de avance sin catch-up con `Clock` controlado.
- `EXPLAIN` del claim con el índice parcial.
- Benchmarks B1 a B5 y B10: lag p95, `opswatch_scheduler_claim_duration_seconds` y vencidos. B10 valida el comportamiento con dos instancias a escala.

## 10. ¿Qué tendría que pasar para reconsiderarla?

- El claim p95 > 50 ms o su carga se vuelve significativa en PostgreSQL a la escala objetivo.
- Más de ~10 instancias del motor compitiendo (contención en el índice).
- Necesidad de prioridades o de planificación más compleja que un intervalo fijo.
- Extracción de Monitoring con su propia base de datos: el mecanismo se mantiene, pero se mueve con el servicio.
