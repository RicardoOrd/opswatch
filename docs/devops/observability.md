# Observabilidad

Estado: diseño inicial · Última revisión: 2026-09-28

Sin mediciones, la evolución de la arquitectura sería opinión. Por eso las métricas del motor existen **desde la Fase 3**, aunque el stack de Prometheus y Grafana llegue en la Fase 7.

| Pilar | V1 (Fases 0–6) | Fase 7 | Etapa 3 y posteriores |
|---|---|---|---|
| Logs | JSON estructurado con `requestId`, a stdout, con rotación de Docker | Igual. Loki opcional | Agregación centralizada |
| Métricas | Micrometer y `/actuator/prometheus` en el puerto de management | Prometheus y Grafana en Compose, dashboards y alertas | Por servicio |
| Trazas | No | OpenTelemetry con muestreo bajo, para practicar y ver las consultas | **Imprescindibles** entre servicios |
| Salud | `liveness` y `readiness` de Actuator | Igual | Igual por servicio |

## Logs

### Formato

- `local` y `test`: texto legible.
- `staging` y `production`: **JSON** con el logging estructurado nativo de Spring Boot (`logging.structured.format.console=ecs`), sin dependencias extra. Se escribe en stdout y Docker se encarga de la rotación (`max-size: 10m`, `max-file: 5`).

### Campos de contexto (MDC)

| Campo | De dónde sale | Cuándo |
|---|---|---|
| `requestId` | `RequestIdFilter` (lo reutiliza de `X-Request-Id` si es válido o lo genera) | Toda petición HTTP |
| `userId` | `CurrentUser`, después de autenticar | Peticiones autenticadas |
| `organizationId` | Caso de uso, después de resolver el tenant | Cuando aplica |
| `monitorId` | Motor de checks | Cada ejecución de check |
| `traceId`, `spanId` | Micrometer Tracing | Desde la Fase 7 |
| `event.category` | Explícito | `security` en los eventos de seguridad |

En los virtual threads del motor, el MDC se fija al principio de cada tarea y se limpia en un `finally`: no se hereda del dispatcher.

### Niveles

| Nivel | Uso |
|---|---|
| `ERROR` | Algo que requiere actuar: fallo inesperado, error interno en un check, publicación de evento que falla una y otra vez |
| `WARN` | Anomalía recuperable: un reintento de entrega, un bloqueo de SSRF, un rate limit alcanzado |
| `INFO` | Hitos del ciclo de vida: arranque, migraciones, transiciones de monitor (`DOWN` y recuperado), incidentes, despliegues |
| `DEBUG` | Diagnóstico en local. Nunca activo por defecto en producción |

**No** se registra un `INFO` por cada check: a 167 checks por segundo sería ruido caro. Los checks individuales están en `monitor_checks` y en las métricas.

### Lo que nunca aparece en un log

Contraseñas, hashes, tokens (access y refresh), cookies, valores de headers de monitores, configuración de canales (URL de webhooks, secretos, destinatarios), cuerpos de peticiones de autenticación y la query string de las URL de monitores. Detalle y tests en [Arquitectura de seguridad](../security/security-architecture.md#9-logs-y-datos-sensibles).

### Eventos de seguridad

Se registran con `event.category=security` para poder filtrarlos:

- `auth.login.failed` (email con hash, IP), `auth.refresh.reuse_detected`, `auth.rate_limited` (límite, IP y, en el del email, su hash; uno por ráfaga, no por petición rechazada);
- `authz.denied` (usuario, organización, permiso);
- `egress.blocked` (monitor, host y rango, sin la query string);
- `membership.role_changed`, `membership.removed`.

## Métricas

### Exposición

- `/actuator/prometheus` en el **puerto de management 8081**, que no se publica fuera del host. Prometheus (Fase 7) lo alcanza por la red interna.
- Endpoints de Actuator expuestos: `health`, `info` y `prometheus`. Nada más (`env`, `heapdump`, `configprops` y el resto, desactivados).

### Métricas incluidas por Spring Boot

- **JVM:** memoria por región, GC, hilos (incluidos los virtual threads si la versión los expone), clases cargadas.
- **HTTP servidor:** `http_server_requests_seconds` por URI plantilla, método y estado.
- **Pool de base de datos:** `hikaricp_connections_active`, `_idle`, `_pending` y `_acquire_seconds`.
- **Proceso:** CPU y descriptores de fichero.
- **Tareas programadas:** `tasks_scheduled_execution_seconds`.

### Métricas propias

| Métrica | Tipo | Etiquetas | Fase |
|---|---|---|---|
| `opswatch_monitor_checks_total` | counter | `outcome`, `reason` | 3 |
| `opswatch_monitor_check_duration_seconds` | histogram | `outcome` | 3 |
| `opswatch_monitor_check_lag_seconds` | histogram | — | 3 |
| `opswatch_monitor_checks_in_flight` | gauge | — | 3 |
| `opswatch_monitor_checks_overdue` | gauge | — | 3 |
| `opswatch_scheduler_claim_duration_seconds` | histogram | — | 3 |
| `opswatch_scheduler_dispatcher_saturated_total` | counter | — | 3 |
| `opswatch_egress_blocked_total` | counter | `reason` | 3 |
| `opswatch_retention_deleted_rows_total` | counter | `table` | 3 |
| `opswatch_retention_duration_seconds` | histogram | `table` | 3 |
| `opswatch_incidents_active` | gauge | — | 4 |
| `opswatch_incidents_opened_total` | counter | — | 4 |
| `opswatch_notification_deliveries_total` | counter | `channel_type`, `result` | 4 |
| `opswatch_event_publications_incomplete` | gauge | — | 2 |
| `opswatch_auth_login_total` | counter | `result` | 1 |

Correspondencia con los ejemplos de la especificación inicial:

- `opswatch_monitor_checks_total` y `opswatch_incidents_active` se mantienen tal cual.
- `opswatch_monitor_check_duration` es `opswatch_monitor_check_duration_seconds`: la convención de Prometheus pide la unidad en el nombre.
- `opswatch_monitor_failures_total` **no se crea**: es `opswatch_monitor_checks_total{outcome="DOWN"}`. Duplicar el contador sería otra cosa que mantener sincronizada.

Los gauges que consultan la base de datos (`overdue`, `incidents_active`, `event_publications_incomplete`) se recalculan cada 15 a 30 s con una tarea programada. No se calculan en cada scrape.

### Reglas de cardinalidad

- **Nunca** se etiqueta con `monitorId`, `organizationId`, `userId`, URL ni host de destino. Con miles de valores, Prometheus se degrada y el costo crece.
- Las etiquetas solo toman valores de conjuntos pequeños y cerrados: `outcome`, `reason`, `channel_type`, `result`, `table`.
- Para investigar un monitor concreto se usa la API o SQL, no las métricas.

### Histogramas

Se configuran con buckets explícitos (SLO buckets de Micrometer) en lugar de percentiles precalculados en el cliente, para poder agregarlos entre instancias en Prometheus:

- lag: 0,1 · 0,25 · 0,5 · 1 · 2 · 5 · 10 · 30 · 60 s;
- duración de los checks: 0,05 · 0,1 · 0,25 · 0,5 · 1 · 2,5 · 5 · 10 · 30 s.

## Salud

| Grupo | Incluye | Uso |
|---|---|---|
| `liveness` | Solo el estado interno de la aplicación | Healthcheck de Docker: si falla, se reinicia el contenedor |
| `readiness` | La aplicación más PostgreSQL | Caddy y los smoke tests: si falla, no se envía tráfico |

La base de datos **no** entra en `liveness`: si PostgreSQL cae, reiniciar la aplicación no lo arregla y provocaría reinicios en cascada.

## Trazas (Fase 7 en adelante)

- Micrometer Tracing con el puente de OpenTelemetry y exportación OTLP (el starter concreto depende de la versión de Spring Boot fijada) a un backend ligero en Compose: Grafana Tempo o Jaeger.
- En el monolito aportan poco más que los logs con `requestId`. Muestreo al 10 %, para aprender el stack y ver el tiempo en base de datos frente al tiempo en HTTP de los checks.
- En la Etapa 3 pasan a ser **requisito previo** a la extracción: el paso 1 del plan de extracción incluye la propagación de contexto (`traceparent`) por HTTP y, después, por el broker ([evolución](../architecture/evolution.md)).

## Dashboards y alertas (Fase 7)

| Dashboard | Paneles |
|---|---|
| Motor | Checks por segundo por `outcome`, lag p50/p95/p99, en vuelo contra el límite, vencidos, saturación del dispatcher, bloqueos de egress |
| API | Peticiones por segundo, p95 por endpoint, errores 4xx/5xx, rate limits |
| JVM | Heap, GC, hilos, CPU |
| Base de datos | Pool (activas, pendientes, tiempo de adquisición), tamaño de `monitor_checks` y consultas más lentas desde `pg_stat_statements` |
| Negocio | Incidentes activos, incidentes abiertos por hora, entregas fallidas |

| Alerta | Condición | Por qué |
|---|---|---|
| Lag del motor | p95 > 5 s durante 10 min | El motor no da abasto |
| Saturación | Checks en vuelo ≥ 90 % del límite durante 10 min | A punto de acumular retraso |
| Errores internos del motor | `outcome="ERROR"` > 0,1 % durante 5 min | Bug propio |
| Pool de base de datos | `pending` > 0 sostenido 5 min | Transacciones largas o pool pequeño |
| Publicaciones de eventos atascadas | Incompletas de más de 10 min > 0 | Listener que falla una y otra vez |
| Entregas fallidas | `result="FAILED"` crece | Canal roto |
| Bloqueos de SSRF | Tasa anómala | Posible abuso |

## ¿Quién vigila al vigilante?

Si OpsWatch cae, sus propias alertas no se envían. Mitigación barata: un monitor externo gratuito de otro proveedor contra `/actuator/health/readiness`, publicado por Caddy con una ruta restringida, o contra la home. Se configura en la Fase 6.
