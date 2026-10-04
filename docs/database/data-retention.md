# Retención y crecimiento de datos

Estado: diseño inicial · Última revisión: 2026-10-02 · Decisión: [ADR-008](../adr/ADR-008-check-results-storage.md)

## Crecimiento esperado de `monitor_checks`

A unos 120 bytes por fila entre tabla e índice (estimación que se valida en la Fase 7):

| Monitores | Intervalo | Filas/día | Tamaño/día | 30 días de datos crudos |
|---|---|---|---|---|
| 100 | 60 s | 144 000 | ~17 MB | ~0,5 GB |
| 1 000 | 60 s | 1,44 M | ~170 MB | ~5 GB |
| 5 000 | 60 s | 7,2 M | ~860 MB | ~26 GB |
| 10 000 | 60 s | 14,4 M | ~1,7 GB | ~52 GB |
| 10 000 | 30 s | 28,8 M | ~3,5 GB | ~104 GB |

Lectura:

- **Con 100 monitores** (el uso real previsible del proyecto), el crecimiento es irrelevante.
- **Con 1 000**, una tabla única con retención de 30 días es cómoda en un VPS pequeño.
- **Con 5 000 o más**, la purga diaria borra millones de filas y el disco pasa a ser un costo real. Es la escala en la que el particionado y los rollups dejan de ser teoría.
- Los escenarios de 5 000 y 10 000 se ejecutan en los **benchmarks** (Fase 7), no en producción. Sirven para demostrar dónde se rompe cada estrategia.

## Estrategia inicial (V1)

| Dato | Retención | Mecanismo |
|---|---|---|
| `monitor_checks` | **30 días** (`opswatch.retention.checks`) | Job diario de purga en lotes |
| `monitor_state` | Mientras exista el monitor | Se purga junto con el monitor borrado |
| `incidents`, `incident_timeline` | Indefinida mientras exista la organización | — |
| `notification_deliveries` | 90 días (`opswatch.retention.deliveries`) | Job diario |
| `refresh_tokens` | Hasta 7 días después de caducar o revocarse | Job diario |
| `event_publication_archive` (publicaciones completadas) | 7 días (`opswatch.retention.event-publications`) | Job que purga solo el archivo. Las pendientes de `event_publication` nunca se purgan |
| Monitores borrados lógicamente | Sus checks se purgan en el siguiente ciclo del job y la fila de `monitors` se conserva mientras la referencien incidentes | Job diario |
| Organizaciones borradas | Purga física a los 30 días | Después de V1 |

### Job de purga de checks

```sql
-- Se repite hasta que afecta a 0 filas. Cada lote es una transacción corta.
DELETE FROM monitor_checks
WHERE ctid = ANY (ARRAY(
    SELECT ctid
    FROM monitor_checks
    WHERE checked_at < :cutoff
    LIMIT :batchSize          -- 10 000 por defecto
    FOR UPDATE SKIP LOCKED    -- otra instancia que purga a la vez se lleva otras filas
));
```

- Se ejecuta de madrugada (UTC) con `@Scheduled(cron = …)`. **Sin advisory lock** (decisión de Ricardo del 2026-10-03, igual que las purgas de refresh tokens y del archivo de eventos). Borrar es idempotente, y con `SKIP LOCKED` dos instancias que purgan a la vez se reparten las filas sin repetir trabajo ni esperarse. Un lock de sesión obligaría a retener una conexión durante todos los lotes.
- Después de los checks antiguos, los de los monitores borrados, en lotes, y al final su `monitor_state`. Un check en vuelo que vuelve cuando el estado ya no existe se descarta sin error.
- Lotes pequeños: no retiene locks largos y el autovacuum puede ir recuperando espacio.
- El índice BRIN sobre `checked_at` localiza las filas viejas sin recorrer la tabla.
- Métricas: `opswatch_retention_deleted_rows_total{table}` y `opswatch_retention_duration_seconds{table}`.
- El borrado por `ctid` evita depender de una PK simple, que `monitor_checks` no tiene. Si el patrón diera problemas, la alternativa es borrar por `(monitor_id, checked_at)` con un rango de tiempo acotado.

### Cálculo del uptime en V1

Directamente sobre los datos crudos, con la PK:

```sql
SELECT count(*)                                              AS total,
       count(*) FILTER (WHERE status = 'UP')                 AS up,
       count(*) FILTER (WHERE status = 'DEGRADED')           AS degraded,
       count(*) FILTER (WHERE status = 'DOWN')               AS down,
       percentile_cont(0.95) WITHIN GROUP (ORDER BY response_time_ms)
           FILTER (WHERE response_time_ms IS NOT NULL)       AS p95_ms
FROM monitor_checks
WHERE monitor_id = :monitorId
  AND checked_at >= :from
  AND checked_at <  :to;
```

Para 30 días de un monitor a 30 s son unas 86 400 filas contiguas en el índice. Se espera que tarde decenas de milisegundos y se mide en la Fase 7. Las ventanas disponibles están limitadas por la retención: no se ofrece uptime de 90 días con 30 días de datos crudos.

## Estrategia futura

Ninguna de estas medidas entra sin su disparador.

### Rollups por hora

```text
monitor_check_rollups_hourly (
    monitor_id, bucket_start,                      -- PK
    total, up, degraded, down,
    response_time_sum_ms, response_time_max_ms,
    latency_histogram smallint[] / int[]           -- conteos por buckets fijos de latencia
)
```

- **Disparador:** `GET /api/v1/monitors/{id}/stats?window=30d` con p95 > 200 ms, dashboards que calculan el uptime de muchos monitores a la vez, o la necesidad de ventanas más largas que la retención cruda (90 días o 12 meses).
- **Percentiles:** no se pueden agregar sumando percentiles por hora. Se guarda un histograma de buckets fijos (por ejemplo, 12 buckets de 0 a 30 s en escala logarítmica) y los percentiles de ventanas largas se aproximan desde ahí. La precisión se documenta.
- **Alimentación:** un job cada hora agrega la hora cerrada anterior. Es idempotente (`INSERT … ON CONFLICT DO UPDATE`).
- **Retención:** 13 meses.
- **Módulo:** es el embrión natural de `analytics` ([evolución](../architecture/evolution.md)).

### Particionado

```sql
CREATE TABLE monitor_checks (...) PARTITION BY RANGE (checked_at);
CREATE TABLE monitor_checks_2026_10_01 PARTITION OF monitor_checks
    FOR VALUES FROM ('2026-10-01') TO ('2026-10-02');
```

- **Disparador (cualquiera):** más de ~100 M filas; una purga diaria de más de 10 min o que genera un bloat significativo; autovacuum incapaz de seguir el ritmo de `monitor_checks` (`n_dead_tup` creciendo de un día para otro).
- **Beneficio:** la retención pasa a ser `DROP TABLE` de la partición vieja (instantáneo, sin bloat) y las consultas por rango solo tocan las particiones relevantes.
- **Granularidad:** diaria a partir de unos 10 M filas por día. Semanal por debajo.
- **Gestión:** un job propio que crea las particiones con antelación y borra las viejas, o `pg_partman` si está disponible en el entorno.
- **Migración de la tabla existente:** tabla nueva particionada, doble escritura durante la ventana de retención y cambio de nombre. O, más simple, adjuntar la tabla actual como partición histórica.
- **La PK actual ya incluye `checked_at`**, que PostgreSQL exige en tablas particionadas.

### Archivado

- **Disparador:** necesidad de conservar el histórico crudo más allá de la retención, por auditoría, para SLA con clientes o para análisis retrospectivo.
- **Forma:** exportar las particiones viejas a ficheros (CSV comprimido o Parquet) en almacenamiento de objetos barato antes de borrarlas.
- **Costo:** almacenamiento de objetos y un proceso de exportación que mantener. Para un portafolio, probablemente nunca haga falta.

### Bases de datos de series temporales

| Opción | Cuándo tendría sentido | Costo |
|---|---|---|
| **TimescaleDB** (extensión de PostgreSQL) | Si el particionado y los rollups manuales se vuelven complejos de mantener. Aporta hypertables, compresión y agregados continuos | Cambio de imagen de PostgreSQL. Algunas funciones van con licencia propia de Timescale y no con la de PostgreSQL: hay que revisar cuáles |
| **ClickHouse** | Analytics sobre cientos de millones o miles de millones de checks con consultas ad hoc | Otro motor que operar. Encaja con un servicio de Analytics (Etapa 5) |
| **Prometheus / VictoriaMetrics** | **No** para los datos de producto: están pensados para métricas operativas con baja cardinalidad, y los checks por monitor son datos de negocio con alta cardinalidad | — |

**Regla:** PostgreSQL sigue siendo la fuente de verdad del estado y los incidentes. Un almacén de series temporales, si llega, guarda solo el histórico de checks y los agregados.
