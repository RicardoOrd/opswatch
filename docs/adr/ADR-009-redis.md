# ADR-009: Redis

- **Estado:** Propuesto (no adoptado). Se decide en la Fase 9 o al desplegar una segunda instancia
- **Fecha:** 2026-09-28
- **Relacionado:** [Evolución, Etapa 2](../architecture/evolution.md#etapa-2-async-cache-y-observabilidad-v2) · [ADR-006](ADR-006-check-scheduling.md) · [Arquitectura de seguridad](../security/security-architecture.md#8-rate-limiting)

## 1. ¿Qué problema existe?

**Hoy ninguno.** Este ADR existe para que Redis no entre "porque toca" en la Etapa 2. Define qué problemas lo justificarían y qué alternativas hay que probar antes.

Problemas candidatos:

| # | Problema | Cuándo aparece |
|---|---|---|
| P1 | Rate limiting coherente entre instancias | Con más de una instancia de la aplicación, los límites en memoria se multiplican por N |
| P2 | Cache del estado de los monitores o de la autorización | Si las lecturas del dashboard o la consulta de membresía pesan de forma medible sobre PostgreSQL |
| P3 | Fan-out del tiempo real entre instancias | Fase 8 con varias instancias: el evento ocurre en A y el cliente está conectado a B |
| P4 | Locks distribuidos o coordinación entre workers | **Resuelto sin Redis** con `SKIP LOCKED` y advisory locks ([ADR-006](ADR-006-check-scheduling.md)) |
| P5 | Sesiones | **No aplica**: la autenticación no tiene estado de sesión ([ADR-004](ADR-004-security-strategy.md)) |
| P6 | Datos muy temporales (último estado, contadores efímeros) | Si su escritura en PostgreSQL resulta cara a gran escala |

## 2. ¿Cuáles son los requisitos?

- PostgreSQL sigue siendo la **fuente de verdad**. Redis, si entra, solo guarda datos derivados o efímeros que se pueden perder.
- Si Redis se cae, la aplicación **se degrada, no se rompe**: el rate limiting sigue en memoria y la cache se salta.
- Justificación medida para cada uso concreto.

## 3. ¿Qué alternativas tenemos?

| Problema | Sin infraestructura nueva | Con Redis |
|---|---|---|
| P1 Rate limiting | Bucket4j en memoria (una instancia). Bucket4j sobre PostgreSQL (varias instancias, a costa de latencia y carga en la base de datos). Rate limiting en el reverse proxy | Bucket4j sobre Redis: atómico y rápido |
| P2 Cache | Caffeine en memoria con TTL corto. Mejores consultas o índices | Cache compartida entre instancias |
| P3 Fan-out | `LISTEN/NOTIFY` de PostgreSQL. Sticky sessions en el proxy | Pub/Sub de Redis o Redis Streams |
| P6 Datos temporales | `monitor_state` en PostgreSQL (ya existe) | Claves con TTL |

## 4 y 5. Ventajas y desventajas

| | Alternativas sin Redis | Redis |
|---|---|---|
| Ventajas | Sin componentes nuevos. Una sola fuente de verdad. Nada más que operar | Muy rápido. Estructuras adecuadas (contadores atómicos, TTL, pub/sub, streams). Soporte maduro en Spring Data Redis y Bucket4j |
| Desventajas | Caffeine no se comparte entre instancias. `LISTEN/NOTIFY` tiene límites de payload y de rendimiento. Bucket4j sobre PostgreSQL carga la base de datos | Otro componente que operar, vigilar y securizar (sin exposición, con autenticación). Riesgo de convertirlo en una segunda fuente de verdad. Consistencia de la cache |

## 6. ¿Qué elegimos?

**Nada por ahora.** V1 usa Caffeine y Bucket4j en memoria con una instancia y `SKIP LOCKED` para la coordinación.

Redis se adopta **solo** si se da al menos uno de estos disparadores, **y** la alternativa sin infraestructura nueva se probó y no alcanza:

| Disparador | Umbral |
|---|---|
| D1 | Se despliega más de una instancia que sirve la API (P1) |
| D2 | La consulta de membresía o las lecturas del dashboard superan el 30 % de la CPU de PostgreSQL, o suben el p95 de la API más de un 20 %, con Caffeine ya probado (P2) |
| D3 | Tiempo real con varias instancias y `LISTEN/NOTIFY` con p95 de entrega > 1 s o errores (P3) |

## 7. ¿Por qué?

Ningún problema de la lista existe en V1. P4 y P5 no existirán nunca con este diseño. Introducir Redis ahora añadiría operación y un riesgo de inconsistencia sin beneficio.

## 8. ¿Qué costo o complejidad introduce, si se adopta?

- Un contenedor más, con autenticación y sin puerto publicado.
- La estrategia de invalidación de cada cache.
- Tests con Testcontainers para Redis.
- Memoria en el VPS.

## 9. ¿Cómo comprobaremos que funciona, si se adopta?

- Benchmark antes y después con el mismo escenario (por ejemplo, B7 con dos instancias).
- Test de caída de Redis: la aplicación sigue funcionando degradada.
- Métricas de aciertos y fallos de la cache.

## 10. ¿Qué tendría que pasar para reconsiderarla?

Cualquiera de los disparadores D1 a D3. Si ocurren, este ADR pasa a "Aceptado" con el uso concreto y los datos que lo justifican.
