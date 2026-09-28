# ADR-008: Resultados de checks en una tabla con retención

- **Estado:** Aceptado
- **Fecha:** 2026-09-28
- **Relacionado:** [Diseño de base de datos](../database/database-design.md#7-monitor_checks) · [Retención](../database/data-retention.md) · [ADR-002](ADR-002-postgresql.md)

## 1. ¿Qué problema existe?

Cada check produce un resultado. Con 1 000 monitores a 60 s son 1,44 millones de filas al día, y con 10 000 son 14,4 millones. Hay que guardarlas para el historial, el uptime y los percentiles sin que el almacenamiento, la purga o las consultas se conviertan en un problema.

## 2. ¿Cuáles son los requisitos?

- Inserción barata (hasta ~170 por segundo a 10 000 monitores y 60 s).
- Consultas rápidas: últimos N checks de un monitor, y uptime y percentiles de un monitor en 24 h, 7 d o 30 d.
- Retención configurable con una purga que no bloquee ni genere bloat descontrolado.
- Crecimiento futuro sin migraciones traumáticas.
- Sin infraestructura nueva en V1.

## 3. ¿Qué alternativas tenemos?

1. **Una tabla en PostgreSQL con PK `(monitor_id, checked_at)`, BRIN en `checked_at` y retención por borrado en lotes.**
2. Tabla particionada por tiempo desde el primer día.
3. TimescaleDB (hypertable).
4. Base de datos de series temporales o analítica aparte (InfluxDB, ClickHouse…).
5. No guardar cada check: solo los cambios de estado más contadores agregados.

## 4 y 5. Ventajas y desventajas

| Alternativa | Ventajas | Desventajas |
|---|---|---|
| **Tabla única + retención** | Simple. Una PK que sirve a todas las consultas. BRIN diminuto para la purga. Preparada para particionar (la PK ya incluye `checked_at`) | La purga por `DELETE` genera tuplas muertas. A gran escala es más cara que borrar particiones |
| Particionado desde el principio | Retención instantánea con `DROP` y sin bloat | Gestión de particiones (crearlas por adelantado, borrarlas) y más complejidad **sin necesidad a la escala real de V1** |
| TimescaleDB | Hypertables, compresión y agregados continuos | Imagen de PostgreSQL distinta. Parte de las funciones con licencia propia de Timescale. Otra pieza que aprender y operar antes de necesitarla |
| TSDB o analítica aparte | Escala enorme y compresión | Otra base de datos, sincronización y costo. Sobredimensionado |
| Solo cambios de estado | Poquísimo volumen | Se pierde el historial de latencia y los percentiles, que son funcionalidad de producto |

## 6. ¿Qué elegimos?

Tabla `monitor_checks` **sin id propio**, con **PK `(monitor_id, checked_at)`**, **BRIN en `checked_at`**, inserción por JDBC sin pasar por JPA, sin actualizaciones y **retención de 30 días** con un job diario que borra en lotes de 10 000.

## 7. ¿Por qué?

- A la escala real prevista (cientos de monitores) el volumen es trivial, y a 1 000 monitores sigue siendo cómodo (~5 GB en 30 días).
- La PK compuesta sirve a la vez como restricción y como índice de todas las consultas, sin un segundo B-tree.
- El diseño ya es compatible con el particionado: pasar a él es un cambio de almacenamiento, no de modelo.
- Mantiene V1 en una aplicación y una base de datos.

## 8. ¿Qué costo o complejidad introduce?

- Job de retención con su métrica y un advisory lock para que no corra dos veces.
- Bloat moderado tras cada purga (lo gestiona el autovacuum y se vigila).
- Las ventanas de estadísticas quedan limitadas por la retención (no hay uptime de 90 días sin rollups).
- Clave dependiente de la precisión del timestamp: se trunca a microsegundos en la aplicación.

## 9. ¿Cómo comprobaremos que funciona?

- Benchmarks: p95 de la inserción, tamaño real por fila (hipótesis de ~120 bytes), duración de la purga, `n_dead_tup` después de purgar y `GET /stats?window=30d` con 86 400 filas (B9).
- Tests: purga en lotes correcta, cursor por `checked_at` y estadísticas con datos conocidos.

## 10. ¿Qué tendría que pasar para reconsiderarla?

- **Particionado:** más de ~100 M filas, una purga de más de 10 min, o el autovacuum sin seguir el ritmo.
- **Rollups por hora:** p95 de `/stats?window=30d` > 200 ms, o necesidad de ventanas más largas que la retención.
- **TimescaleDB o ClickHouse:** que el particionado y los rollups manuales se vuelvan difíciles de mantener, o necesidades analíticas de la Etapa 5.
