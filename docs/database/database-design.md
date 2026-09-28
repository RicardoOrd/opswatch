# Diseño de base de datos

Estado: diseño inicial · Última revisión: 2026-09-28 · Decisiones: [ADR-002](../adr/ADR-002-postgresql.md), [ADR-008](../adr/ADR-008-check-results-storage.md)

El modelo conceptual está en [Modelo de dominio](../architecture/domain-model.md). Este documento baja a PostgreSQL: tipos, claves, índices, bloqueos y un DDL preliminar. **El DDL es un borrador de diseño.** La versión válida será la de `src/main/resources/db/migration` cuando exista.

## 1. Por qué PostgreSQL

Resumen de [ADR-002](../adr/ADR-002-postgresql.md):

| Necesidad de OpsWatch | PostgreSQL | MySQL 8 | MongoDB | Cassandra |
|---|---|---|---|---|
| Integridad multi-tenant (FK, FK compuestas, `CHECK`) | Completa | Completa, con `CHECK` desde 8.0.16 | Sin FK: en la aplicación | Sin FK ni transacciones entre particiones |
| "Un incidente activo por monitor" como restricción | **Índice único parcial** | No hay índices parciales (sí, con columnas generadas y trucos) | Índice único parcial | No |
| Cola de trabajo concurrente (`FOR UPDATE SKIP LOCKED`) | Sí | Sí (8.0+) | Con otros patrones (`findAndModify`) | No |
| Percentiles en SQL (`percentile_cont`) | Sí | No de forma nativa | Sí, en las versiones recientes del pipeline de agregación | No |
| Índices BRIN para series temporales | Sí | No | No | No aplica |
| UUIDv7 nativo (`uuidv7()`) | Sí (PostgreSQL 18) | No | No aplica | Tipos `timeuuid` |
| Particionado declarativo para crecer | Sí | Sí | Sharding | Nativo, a costa del modelo relacional |
| Camino a series temporales sin cambiar de motor | TimescaleDB (extensión) | No | Colecciones time series | Es su punto fuerte, pero es un salto enorme |

Cassandra resolvería un volumen de escritura de checks que OpsWatch no tendrá en ninguna etapa realista del proyecto, a costa de perder transacciones y relaciones en todo lo demás. MongoDB no aporta nada que falte y quita integridad. MySQL sería viable, pero pierde los índices parciales, los percentiles, BRIN y la extensión de series temporales.

## 2. Convenciones

| Elemento | Convención | Ejemplo |
|---|---|---|
| Tablas | `snake_case` en plural | `monitor_checks` |
| Columnas | `snake_case` | `next_check_at` |
| PK | `id uuid`, salvo las tablas de relación o de series | `memberships (organization_id, user_id)` |
| FK | `fk_<tabla>_<referencia>` | `fk_monitors_project` |
| Índice | `ix_<tabla>_<columnas o propósito>` | `ix_monitor_state_due` |
| Índice único | `ux_<tabla>_<columnas>` | `ux_users_email` |
| `CHECK` | `ck_<tabla>_<regla>` | `ck_monitors_interval` |
| Enums | `text` con `CHECK (col IN (...))` | `status text` |
| Tiempo | `timestamptz` | `created_at` |
| Esquema | `public` en V1. Esquemas por módulo solo al preparar una extracción | — |

### Enums como `text` con `CHECK` y no como `ENUM` de PostgreSQL

- Añadir un valor es cambiar un `CHECK` en una migración normal. Con `ALTER TYPE … ADD VALUE` había restricciones dentro de transacciones y no se pueden quitar valores.
- Hibernate lo mapea con `@Enumerated(STRING)` sin tipos personalizados.
- Costo: unos bytes más por fila, despreciable salvo en `monitor_checks`, donde `'UP'`, `'DOWN'` y `'DEGRADED'` ocupan entre 3 y 9 bytes.

## 3. UUID o bigint

| Criterio | `bigint` identity | UUIDv4 | **UUIDv7** |
|---|---|---|---|
| Tamaño | 8 bytes | 16 bytes | 16 bytes |
| Localidad en el índice B-tree | Excelente | Mala (inserciones aleatorias, páginas partidas) | Buena (prefijo temporal) |
| Se puede generar en la aplicación antes de persistir | No (necesita la secuencia) | Sí | Sí |
| Revela el volumen o permite enumerar | Sí | No | Revela el instante de creación, no el volumen |
| Válido para ids distribuidos (Etapa 3) | Colisiones entre servicios | Sí | Sí |

**Decisión:** UUIDv7 generado en la aplicación para todas las entidades. Opciones que se verifican en el Sprint 0: el generador de Hibernate para UUID versión 7, si la versión fijada lo ofrece, o un generador propio de unas 20 líneas. PostgreSQL 18 también tiene `uuidv7()` para defaults en SQL.

**Excepción:** `monitor_checks` no tiene id propio. Su PK es `(monitor_id, checked_at)`. Ver la sección 7.

## 4. Tiempo y zona horaria

- Siempre `timestamptz`, que guarda un instante absoluto (internamente en UTC).
- Java usa `Instant`, nunca `LocalDateTime` para instantes.
- `hibernate.jdbc.time_zone=UTC` y JVM con `-Duser.timezone=UTC` en los contenedores, para evitar sorpresas en las conversiones.
- La API usa ISO-8601 con `Z`. La conversión a la zona del usuario se hace en el cliente.
- `created_at` y `updated_at` los fija la aplicación con el `Clock` inyectado, no la base de datos, para que los tests sean deterministas.
- Precisión: microsegundos (la de PostgreSQL). `Instant` tiene nanosegundos: se truncan a microsegundos **antes** de usarlos como clave (`checked_at`), para que lo que se lee coincida con lo que se escribió.

## 5. Borrado lógico o físico

| Tabla | Borrado | Motivo |
|---|---|---|
| `organizations`, `projects`, `monitors` | **Lógico** (`deleted_at`) | Tienen historial dependiente (checks, incidentes) que debe sobrevivir, y borrar millones de checks dentro de una petición HTTP sería inaceptable |
| `memberships`, `notification_channels`, `refresh_tokens` | Físico | Sin historial que conservar. El audit log (Fase 5) registrará quién los borró |
| `monitor_checks` | Físico, por retención | Serie temporal |
| `incidents`, `incident_timeline` | Se conservan | Historial |

Consecuencias del borrado lógico:

- Los índices únicos de nombre son **parciales** (`WHERE deleted_at IS NULL`) para que se pueda reutilizar un nombre.
- Los repositorios filtran `deleted_at IS NULL` en todas las consultas de negocio con métodos explícitos (`findActiveById`), no con filtros globales de Hibernate, que son fáciles de olvidar al escribir SQL a mano.
- Un job purga los monitores borrados: primero sus `monitor_checks` en lotes, después su estado. La fila de `monitors` se conserva mientras la referencien incidentes.

## 6. Concurrencia

| Mecanismo | Dónde | Por qué |
|---|---|---|
| **Bloqueo optimista** (`version bigint` + `@Version`) | `users`, `organizations`, `memberships`, `projects`, `monitors`, `incidents`, `notification_channels` | Ediciones humanas poco frecuentes. Un conflicto → `409` |
| **Bloqueo de fila pesimista** (`SELECT … FOR UPDATE`) | `monitor_state` en cada resultado, pausa y reanudación. `organizations` al cambiar roles. `refresh_tokens` al rotar | Escrituras frecuentes o invariantes entre filas (último `OWNER`, un solo uso por refresh token) |
| **`FOR UPDATE SKIP LOCKED`** | Claim de `monitor_state` y de `notification_deliveries` | Colas de trabajo con varios consumidores sin coordinación |
| **Restricciones únicas** | Un incidente activo por monitor, una entrega por (canal, incidente, tipo), email único | Última línea de defensa ante carreras e idempotencia |

Transacciones: `READ COMMITTED` (el valor por defecto) con los bloqueos anteriores donde hace falta. No se usa `SERIALIZABLE`: los reintentos por fallos de serialización complicarían todo el código, y los puntos críticos ya están cubiertos.

## 7. `monitor_checks`

Es la tabla que más crece. Su diseño es deliberado:

- **PK compuesta `(monitor_id, checked_at)`.** Es a la vez la restricción de unicidad y el índice de todas las consultas ("últimos N checks de un monitor", "checks de un monitor en una ventana"). Un id `bigint` añadiría 8 bytes por fila más un segundo índice que ninguna consulta necesita. No puede haber dos checks del mismo monitor en el mismo microsegundo porque el scheduler nunca ejecuta dos a la vez.
- **Compatible con el particionado futuro:** PostgreSQL exige que la PK de una tabla particionada incluya la clave de partición, y `checked_at` ya está.
- **BRIN sobre `checked_at`** para la retención (`DELETE … WHERE checked_at < :limite`). Las filas llegan en orden temporal aproximado, así que el BRIN resume rangos de páginas en muy poco espacio. Un B-tree equivalente ocuparía decenas de MB por millón de filas.
- **Inserción por JDBC**, no por JPA ([motor](../architecture/monitoring-engine.md#9-persistencia-del-resultado)).
- **Sin actualizaciones.** Menos bloat y vacuum sencillo.

Tamaño estimado por fila: unos 60 a 80 bytes de tupla (cabecera de 23 bytes, `uuid` de 16, `timestamptz` de 8, `text` corto, `smallint`, `integer`, nulos) más unos 40 a 50 bytes del índice PK. Aproximadamente **120 bytes por check** entre tabla e índice, a falta de medirlo en la Fase 7.

El crecimiento, la retención y la estrategia futura (rollups, particionado, TSDB) están en [data-retention.md](data-retention.md).

## 8. Claves foráneas entre módulos

Dentro del monolito se mantienen por integridad. Son las que habría que sustituir por validación en la aplicación si un módulo se extrae:

| FK | De → a | Afecta a la extracción de |
|---|---|---|
| `memberships.user_id → users` | organization → identity | — |
| `monitors (project_id, organization_id) → projects` | monitoring → organization | **Monitoring** |
| `monitors.created_by → users` | monitoring → identity | **Monitoring** |
| `incidents.monitor_id → monitors` | incident → monitoring | **Monitoring** |
| `incidents (project_id, organization_id) → projects` | incident → organization | — |
| `incidents.acknowledged_by`, `incidents.resolved_by → users` | incident → identity | — |
| `incident_timeline.actor_user_id → users` | incident → identity | — |
| `notification_channels (project_id, organization_id) → projects` | notification → organization | Notifications |
| `notification_deliveries.incident_id → incidents` | notification → incident | Notifications |

## 9. DDL preliminar

```sql
-- ============ identity ============
CREATE TABLE users (
    id                uuid        PRIMARY KEY,
    email             text        NOT NULL,
    display_name      text        NOT NULL,
    password_hash     text        NOT NULL,
    status            text        NOT NULL DEFAULT 'ACTIVE',
    email_verified_at timestamptz,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ck_users_email_normalized CHECK (email = lower(email) AND char_length(email) <= 254),
    CONSTRAINT ck_users_display_name     CHECK (char_length(display_name) BETWEEN 1 AND 100),
    CONSTRAINT ck_users_status           CHECK (status IN ('ACTIVE', 'DISABLED'))
);
CREATE UNIQUE INDEX ux_users_email ON users (email);

CREATE TABLE refresh_tokens (
    id                 uuid        PRIMARY KEY,
    user_id            uuid        NOT NULL,
    family_id          uuid        NOT NULL,
    token_hash         bytea       NOT NULL,
    issued_at          timestamptz NOT NULL,
    expires_at         timestamptz NOT NULL,
    family_expires_at  timestamptz NOT NULL,
    revoked_at         timestamptz,
    revocation_reason  text,
    replaced_by_id     uuid,
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_refresh_tokens_reason CHECK (revocation_reason IN
        ('ROTATED', 'LOGOUT', 'REUSE_DETECTED', 'PASSWORD_CHANGED', 'USER_DISABLED')),
    CONSTRAINT ck_refresh_tokens_revocation CHECK ((revoked_at IS NULL) = (revocation_reason IS NULL))
);
CREATE UNIQUE INDEX ux_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user   ON refresh_tokens (user_id);

-- ============ organization ============
CREATE TABLE organizations (
    id         uuid        PRIMARY KEY,
    name       text        NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    deleted_at timestamptz,
    version    bigint      NOT NULL DEFAULT 0,
    CONSTRAINT ck_organizations_name CHECK (char_length(name) BETWEEN 1 AND 100)
);

CREATE TABLE memberships (
    organization_id uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    role            text        NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT pk_memberships PRIMARY KEY (organization_id, user_id),
    CONSTRAINT fk_memberships_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_memberships_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_memberships_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER'))
);
CREATE INDEX ix_memberships_user ON memberships (user_id);

CREATE TABLE projects (
    id              uuid        PRIMARY KEY,
    organization_id uuid        NOT NULL,
    name            text        NOT NULL,
    description     text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    deleted_at      timestamptz,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT fk_projects_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT ux_projects_id_organization UNIQUE (id, organization_id),
    CONSTRAINT ck_projects_name CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT ck_projects_description CHECK (description IS NULL OR char_length(description) <= 500)
);
CREATE UNIQUE INDEX ux_projects_org_name ON projects (organization_id, lower(name)) WHERE deleted_at IS NULL;
CREATE INDEX ix_projects_organization ON projects (organization_id) WHERE deleted_at IS NULL;

-- ============ monitoring ============
CREATE TABLE monitors (
    id                    uuid        PRIMARY KEY,
    organization_id       uuid        NOT NULL,
    project_id            uuid        NOT NULL,
    name                  text        NOT NULL,
    url                   text        NOT NULL,
    http_method           text        NOT NULL DEFAULT 'GET',
    expected_status_min   smallint    NOT NULL DEFAULT 200,
    expected_status_max   smallint    NOT NULL DEFAULT 299,
    interval_seconds      integer     NOT NULL DEFAULT 60,
    timeout_ms            integer     NOT NULL DEFAULT 10000,
    degraded_threshold_ms integer,
    follow_redirects      boolean     NOT NULL DEFAULT true,
    failure_threshold     smallint    NOT NULL DEFAULT 3,
    recovery_threshold    smallint    NOT NULL DEFAULT 2,
    request_headers       bytea,      -- JSON cifrado con AES-256-GCM (SecretCipher)
    enabled               boolean     NOT NULL DEFAULT true,
    created_by            uuid,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    deleted_at            timestamptz,
    version               bigint      NOT NULL DEFAULT 0,
    CONSTRAINT fk_monitors_project FOREIGN KEY (project_id, organization_id)
        REFERENCES projects (id, organization_id),
    CONSTRAINT fk_monitors_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_monitors_name      CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT ck_monitors_url       CHECK (char_length(url) <= 2048),
    CONSTRAINT ck_monitors_method    CHECK (http_method IN ('GET', 'HEAD')),
    CONSTRAINT ck_monitors_status    CHECK (expected_status_min BETWEEN 100 AND 599
                                        AND expected_status_max BETWEEN 100 AND 599
                                        AND expected_status_min <= expected_status_max),
    CONSTRAINT ck_monitors_interval  CHECK (interval_seconds BETWEEN 30 AND 3600),
    CONSTRAINT ck_monitors_timeout   CHECK (timeout_ms BETWEEN 1000 AND 30000
                                        AND timeout_ms < interval_seconds * 1000),
    CONSTRAINT ck_monitors_degraded  CHECK (degraded_threshold_ms IS NULL
                                        OR degraded_threshold_ms BETWEEN 1 AND timeout_ms),
    CONSTRAINT ck_monitors_thresholds CHECK (failure_threshold BETWEEN 1 AND 10
                                        AND recovery_threshold BETWEEN 1 AND 10)
);
CREATE INDEX ix_monitors_project      ON monitors (project_id)      WHERE deleted_at IS NULL;
CREATE INDEX ix_monitors_organization ON monitors (organization_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX ux_monitors_project_name ON monitors (project_id, lower(name)) WHERE deleted_at IS NULL;

CREATE TABLE monitor_state (
    monitor_id            uuid        PRIMARY KEY,
    status                text        NOT NULL DEFAULT 'PENDING',
    status_changed_at     timestamptz NOT NULL,
    consecutive_failures  integer     NOT NULL DEFAULT 0,
    consecutive_successes integer     NOT NULL DEFAULT 0,
    last_checked_at       timestamptz,
    last_check_status     text,
    last_http_status      smallint,
    last_response_time_ms integer,
    last_failure_reason   text,
    next_check_at         timestamptz,   -- NULL = no programado
    updated_at            timestamptz NOT NULL,
    CONSTRAINT fk_monitor_state_monitor FOREIGN KEY (monitor_id) REFERENCES monitors (id),
    CONSTRAINT ck_monitor_state_status CHECK (status IN ('PENDING', 'UP', 'DEGRADED', 'DOWN', 'PAUSED')),
    CONSTRAINT ck_monitor_state_paused CHECK (status <> 'PAUSED' OR next_check_at IS NULL)
);
CREATE INDEX ix_monitor_state_due ON monitor_state (next_check_at) WHERE next_check_at IS NOT NULL;

CREATE TABLE monitor_checks (
    monitor_id       uuid        NOT NULL,
    checked_at       timestamptz NOT NULL,
    status           text        NOT NULL,
    http_status      smallint,
    response_time_ms integer,
    failure_reason   text,
    error_detail     text,
    CONSTRAINT pk_monitor_checks PRIMARY KEY (monitor_id, checked_at),
    CONSTRAINT fk_monitor_checks_monitor FOREIGN KEY (monitor_id) REFERENCES monitors (id),
    CONSTRAINT ck_monitor_checks_status CHECK (status IN ('UP', 'DEGRADED', 'DOWN')),
    CONSTRAINT ck_monitor_checks_reason CHECK (failure_reason IN ('TIMEOUT', 'DNS_FAILURE', 'CONNECTION_FAILED',
        'TLS_FAILURE', 'UNEXPECTED_STATUS', 'TOO_MANY_REDIRECTS', 'TARGET_BLOCKED', 'PROTOCOL_ERROR')),
    CONSTRAINT ck_monitor_checks_reason_consistency CHECK ((status = 'DOWN') = (failure_reason IS NOT NULL)),
    CONSTRAINT ck_monitor_checks_detail CHECK (error_detail IS NULL OR char_length(error_detail) <= 255)
);
CREATE INDEX ix_monitor_checks_checked_at_brin ON monitor_checks USING brin (checked_at);

-- ============ incident ============
CREATE TABLE incidents (
    id                uuid        PRIMARY KEY,
    organization_id   uuid        NOT NULL,
    project_id        uuid        NOT NULL,
    monitor_id        uuid        NOT NULL,
    monitor_name      text        NOT NULL,
    status            text        NOT NULL,
    cause             text        NOT NULL,
    cause_http_status smallint,
    opened_at         timestamptz NOT NULL,
    acknowledged_at   timestamptz,
    acknowledged_by   uuid,
    resolved_at       timestamptz,
    resolved_by       uuid,
    resolution        text,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT fk_incidents_project FOREIGN KEY (project_id, organization_id) REFERENCES projects (id, organization_id),
    CONSTRAINT fk_incidents_monitor FOREIGN KEY (monitor_id) REFERENCES monitors (id),
    CONSTRAINT fk_incidents_ack_by FOREIGN KEY (acknowledged_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT fk_incidents_resolved_by FOREIGN KEY (resolved_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_incidents_status CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT ck_incidents_resolution CHECK (resolution IN ('AUTO_RECOVERED', 'MONITOR_PAUSED', 'MONITOR_DELETED')),
    CONSTRAINT ck_incidents_resolved CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL AND resolution IS NOT NULL)),
    CONSTRAINT ck_incidents_acknowledged CHECK (status <> 'ACKNOWLEDGED' OR acknowledged_at IS NOT NULL)
);
CREATE UNIQUE INDEX ux_incidents_one_active_per_monitor ON incidents (monitor_id) WHERE status <> 'RESOLVED';
CREATE INDEX ix_incidents_org_opened     ON incidents (organization_id, opened_at DESC);
CREATE INDEX ix_incidents_monitor_opened ON incidents (monitor_id, opened_at DESC);

CREATE TABLE incident_timeline (
    id            uuid        PRIMARY KEY,
    incident_id   uuid        NOT NULL,
    type          text        NOT NULL,
    actor_user_id uuid,
    occurred_at   timestamptz NOT NULL,
    note          text,
    CONSTRAINT fk_incident_timeline_incident FOREIGN KEY (incident_id) REFERENCES incidents (id),
    CONSTRAINT fk_incident_timeline_actor FOREIGN KEY (actor_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_incident_timeline_type CHECK (type IN ('OPENED', 'ACKNOWLEDGED', 'RESOLVED')),
    CONSTRAINT ck_incident_timeline_note CHECK (note IS NULL OR char_length(note) <= 500)
);
CREATE INDEX ix_incident_timeline_incident ON incident_timeline (incident_id, occurred_at);

-- ============ notification ============
CREATE TABLE notification_channels (
    id                uuid        PRIMARY KEY,
    organization_id   uuid        NOT NULL,
    project_id        uuid,       -- NULL = todos los proyectos
    name              text        NOT NULL,
    type              text        NOT NULL,
    config_ciphertext bytea       NOT NULL,
    enabled           boolean     NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT fk_notification_channels_organization FOREIGN KEY (organization_id) REFERENCES organizations (id),
    CONSTRAINT fk_notification_channels_project FOREIGN KEY (project_id, organization_id)
        REFERENCES projects (id, organization_id),   -- MATCH SIMPLE: no se comprueba si project_id es NULL
    CONSTRAINT ck_notification_channels_type CHECK (type IN ('EMAIL', 'WEBHOOK')),
    CONSTRAINT ck_notification_channels_name CHECK (char_length(name) BETWEEN 1 AND 100)
);
CREATE INDEX ix_notification_channels_org ON notification_channels (organization_id);

CREATE TABLE notification_deliveries (
    id              uuid        PRIMARY KEY,
    channel_id      uuid        NOT NULL,
    incident_id     uuid        NOT NULL,
    event_type      text        NOT NULL,
    status          text        NOT NULL DEFAULT 'PENDING',
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    last_attempt_at timestamptz,
    last_error      text,
    created_at      timestamptz NOT NULL,
    sent_at         timestamptz,
    CONSTRAINT fk_notification_deliveries_channel FOREIGN KEY (channel_id)
        REFERENCES notification_channels (id) ON DELETE CASCADE,
    CONSTRAINT fk_notification_deliveries_incident FOREIGN KEY (incident_id) REFERENCES incidents (id),
    CONSTRAINT ux_notification_deliveries_once UNIQUE (channel_id, incident_id, event_type),
    CONSTRAINT ck_notification_deliveries_event CHECK (event_type IN ('INCIDENT_OPENED', 'INCIDENT_RESOLVED')),
    CONSTRAINT ck_notification_deliveries_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_notification_deliveries_error CHECK (last_error IS NULL OR char_length(last_error) <= 255)
);
CREATE INDEX ix_notification_deliveries_due ON notification_deliveries (next_attempt_at) WHERE status = 'PENDING';

-- ============ Spring Modulith ============
-- event_publication: el DDL se copia de la documentación de Spring Modulith
-- para la versión fijada. Su esquema ha cambiado entre versiones mayores.
```

## 10. Consultas críticas y su índice

| Consulta | Índice | Notas |
|---|---|---|
| Claim del scheduler: vencidos por `next_check_at` | `ix_monitor_state_due` (parcial) | Una vez por segundo por instancia. `EXPLAIN` en los tests de repositorio |
| Últimos N checks de un monitor | `pk_monitor_checks` (escaneo hacia atrás) | Paginación por cursor |
| Uptime y percentiles de un monitor en una ventana | `pk_monitor_checks`, rango sobre `checked_at` | 30 d a 30 s son unas 86 400 filas: se mide en la Fase 7 |
| Retención: borrar anteriores a una fecha | `ix_monitor_checks_checked_at_brin` | En lotes |
| Organizaciones del usuario | `ix_memberships_user` | En cada `GET /organizations` |
| Membresía (autorización) | `pk_memberships` | **En cada petición autorizada** |
| Incidentes de una organización | `ix_incidents_org_opened` | Filtros adicionales sobre el resultado |
| Incidente activo de un monitor | `ux_incidents_one_active_per_monitor` | Apertura y resolución |
| Entregas pendientes | `ix_notification_deliveries_due` | Cada 5 s |

Cada consulta de esta tabla tiene un test de repositorio contra PostgreSQL real con datos suficientes para que el planificador elija el índice. Además, en la Fase 7 se revisa `pg_stat_statements` con carga real.

## 11. Pool de conexiones

- HikariCP con 10 conexiones por defecto (el valor de Spring Boot), que se ajusta con datos.
- La regla que hace viable un pool pequeño con muchos checks en vuelo: **no hay transacciones abiertas durante I/O externo** ([motor](../architecture/monitoring-engine.md#9-persistencia-del-resultado)).
- Métricas vigiladas: `hikaricp_connections_pending` y `hikaricp_connections_acquire_seconds`. Si hay esperas sostenidas, se mira primero la duración de las transacciones y después el tamaño del pool.
- `spring.jpa.open-in-view=false`.

## 12. Copias de seguridad (Fase 6)

- `pg_dump` diario comprimido y cifrado a un almacenamiento fuera del VPS. 7 copias diarias y 4 semanales.
- `monitor_checks` es la mayor parte del volumen. Si el tamaño de la copia se vuelve un problema, se valora excluir los datos crudos (se regeneran con el tiempo) y copiar solo los agregados.
- **Una restauración probada** es parte de la Definition of Done de la Fase 6.
