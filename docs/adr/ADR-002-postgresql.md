# ADR-002: PostgreSQL como base de datos principal

- **Estado:** Aceptado
- **Fecha:** 2026-09-28
- **Relacionado:** [Diseño de base de datos](../database/database-design.md) · [Retención](../database/data-retention.md) · [ADR-006](ADR-006-check-scheduling.md) · [ADR-008](ADR-008-check-results-storage.md)

## 1. ¿Qué problema existe?

OpsWatch necesita guardar datos relacionales multi-tenant (usuarios, organizaciones, membresías, monitores, incidentes) y una serie temporal que crece rápido (`monitor_checks`), y además coordinar trabajo concurrente (el scheduler, las entregas).

## 2. ¿Cuáles son los requisitos?

- Integridad referencial y restricciones para el aislamiento entre tenants.
- Restricciones que expresen invariantes de negocio ("un incidente activo por monitor").
- Colas de trabajo concurrentes sin infraestructura adicional.
- Agregaciones con percentiles para las estadísticas.
- Un camino de crecimiento para la serie temporal sin cambiar de motor.
- Open source, fácil en Docker, con buen soporte en Spring, Flyway y Testcontainers.

## 3. ¿Qué alternativas tenemos?

PostgreSQL, MySQL 8, MongoDB, Cassandra.

## 4 y 5. Ventajas y desventajas

| | PostgreSQL | MySQL 8 | MongoDB | Cassandra |
|---|---|---|---|---|
| Ventajas | Índices parciales, `SKIP LOCKED`, `percentile_cont`, BRIN, particionado declarativo, `uuidv7()` nativo (v18), JSONB, extensión de series temporales (TimescaleDB) | Muy extendido, `SKIP LOCKED`, buen rendimiento OLTP | Esquema flexible, escalado horizontal por sharding | Escrituras masivas, escalado lineal, multi-región |
| Desventajas | Vacuum y bloat a vigilar con tablas de alta rotación. Escalado de escritura vertical en un nodo | Sin índices parciales ni percentiles nativos ni BRIN. Menos opciones para la serie temporal | Sin FK. Las invariantes entre documentos quedan en la aplicación. Transacciones con más costo | Sin joins, sin transacciones entre particiones, modelo por consulta. Operación pesada. Resuelve un volumen que el proyecto no tendrá |

## 6. ¿Qué elegimos?

**PostgreSQL 18** como única base de datos de V1 y **fuente de verdad** del sistema.

## 7. ¿Por qué?

- Las invariantes críticas se expresan en la base de datos: índice único parcial (incidente activo), FK compuestas (coherencia del tenant), `CHECK` (rangos de configuración).
- `FOR UPDATE SKIP LOCKED` hace del scheduler y del worker de entregas colas concurrentes sin Redis ni broker ([ADR-006](ADR-006-check-scheduling.md)).
- `percentile_cont` y `FILTER` calculan el uptime y los percentiles en SQL.
- BRIN y el particionado declarativo dan un camino de crecimiento para `monitor_checks`, y TimescaleDB es un salto posible sin cambiar de motor ([ADR-008](ADR-008-check-results-storage.md)).
- Testcontainers permite probar exactamente este comportamiento.

## 8. ¿Qué costo o complejidad introduce?

- Operar PostgreSQL uno mismo: copias de seguridad, restauración y vigilancia del autovacuum.
- La escritura escala en vertical. Es suficiente para la escala prevista y se verifica con benchmarks.
- Las funcionalidades específicas de PostgreSQL atan el código al motor. Es un costo aceptado: cambiar de motor no es un objetivo.

## 9. ¿Cómo comprobaremos que funciona?

- Tests de integración de cada consulta crítica y restricción contra PostgreSQL real.
- Benchmarks: inserciones en `monitor_checks` (p95 < 50 ms del claim y de la inserción), CPU y E/S de PostgreSQL y el tamaño real por fila frente a la estimación (~120 bytes).
- Restauración de copias probada en la Fase 6.

## 10. ¿Qué tendría que pasar para reconsiderarla?

- `monitor_checks` supera lo que el particionado y los rollups manejan en un nodo (se reconsidera **solo el almacén de la serie temporal**, no la base de datos principal).
- Requisitos de escritura multi-región.
- Consultas analíticas sobre el histórico que no caben en el presupuesto de latencia ni con rollups.
