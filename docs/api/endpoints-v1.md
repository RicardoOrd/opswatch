# Catálogo de endpoints `/api/v1`

Estado: diseño inicial · Última revisión: 2026-09-29 · Convenciones: [api-guidelines.md](api-guidelines.md) · Permisos: [authorization-model.md](../security/authorization-model.md)

**Existen `POST /api/v1/auth/register` (OW-012), `POST /api/v1/auth/login` y `GET /api/v1/me` (OW-013). El resto está planificado.** La columna "Fase" indica cuándo se implementa cada uno. Los errores comunes a todos los endpoints autenticados (`401`, `404` a quien no es miembro, `429` y `500`) no se repiten en cada tabla.

## Autenticación (`identity`)

| Método | Ruta | Autenticación | Éxito | Errores específicos | Fase |
|---|---|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Pública | `201` (no inicia sesión) | `400`, `409 conflict` (email registrado), `429` (desde OW-015) | 1 |
| `POST` | `/api/v1/auth/login` | Pública | `200` (la cookie de refresh llega con OW-014) | `400`, `401 invalid-credentials` (el mismo cuerpo exista o no el email), `429` (desde OW-015) | 1 |
| `POST` | `/api/v1/auth/refresh` | Cookie | `200` y cookie nueva | `401` (token inválido, caducado o reutilizado), `403` (`Origin` no permitido) | 1 |
| `POST` | `/api/v1/auth/logout` | Cookie | `204` | — | 1 |
| `GET` | `/api/v1/me` | JWT | `200` | `401` (token ausente, inválido o caducado) | 1 |
| `PATCH` | `/api/v1/me` | JWT | `200` | `400` | 1 |
| `POST` | `/api/v1/me/password` | JWT | `204` (revoca todos los refresh tokens) | `400` (también con la contraseña actual incorrecta, como error de `currentPassword`), `429` | 1 |

```jsonc
// POST /api/v1/auth/register
{ "email": "ana@example.com", "displayName": "Ana", "password": "correct horse battery" }
// 201, Location: /api/v1/me
{ "id": "0192…", "email": "ana@example.com", "displayName": "Ana", "createdAt": "2026-09-28T10:00:00Z" }

// POST /api/v1/auth/login
{ "email": "ana@example.com", "password": "correct horse battery" }
// 200. Desde OW-014, también Set-Cookie: opswatch_refresh=…; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth; Max-Age=1209600
{ "accessToken": "eyJ…", "tokenType": "Bearer", "expiresIn": 900 }

// GET /api/v1/me
{ "id": "0192…", "email": "ana@example.com", "displayName": "Ana", "createdAt": "2026-09-28T10:00:00Z" }

// POST /api/v1/me/password
{ "currentPassword": "correct horse battery", "newPassword": "another long passphrase" }
// 204
```

Validaciones: `email` con formato válido, solo ASCII imprimible y hasta 254 caracteres, normalizado a minúsculas. `displayName` de 1 a 100 caracteres, sin caracteres de control, separadores de línea ni de formato bidireccional. `password` y `newPassword` de 12 caracteres a 72 bytes UTF-8, con el código de error `password-policy`. Los espacios alrededor del email y del nombre se quitan; los de la contraseña, no.

- El access token (RS256, 15 minutos) solo identifica al usuario: no lleva roles ni organizaciones ([ADR-004](../adr/ADR-004-security-strategy.md)). Un token rechazado da `401` con `WWW-Authenticate: Bearer error="invalid_token"`, sin decir por qué.
- Un email inexistente, una contraseña incorrecta y una cuenta deshabilitada dan el mismo `401 invalid-credentials`, y tardan lo mismo: el email inexistente se compara con un hash señuelo del mismo coste.
- `GET /api/v1/me` no incluye las organizaciones del usuario: `identity` no puede depender de `organization` ([módulos](../architecture/modules.md)). Salen, con el rol del usuario en cada una, en `GET /api/v1/organizations`.
- La contraseña actual incorrecta en `POST /api/v1/me/password` da `400` y no `401`: el access token es válido, y un `401` haría que el cliente intentara refrescarlo.

## Organizaciones (`organization`)

| Método | Ruta | Permiso | Éxito | Errores específicos | Fase |
|---|---|---|---|---|---|
| `POST` | `/api/v1/organizations` | Autenticado | `201` (quien la crea queda como `OWNER`) | `400`, `422 quota-exceeded` | 1 |
| `GET` | `/api/v1/organizations` | Autenticado | `200`, paginado (solo aquellas de las que es miembro, con su rol) | — | 1 |
| `GET` | `/api/v1/organizations/{orgId}` | `ORGANIZATION_READ` | `200` | — | 1 |
| `PATCH` | `/api/v1/organizations/{orgId}` | `ORGANIZATION_UPDATE` | `200` | `400`, `403`, `409`, `412` | 1 |
| `DELETE` | `/api/v1/organizations/{orgId}` | `ORGANIZATION_DELETE` | `204` | `403` | 1 |

```jsonc
// POST /api/v1/organizations
{ "name": "CharityLink" }
// 201, Location: /api/v1/organizations/0192…
{ "id": "0192…", "name": "CharityLink", "myRole": "OWNER", "createdAt": "…", "version": 0 }
```

## Miembros

| Método | Ruta | Permiso | Éxito | Errores específicos | Fase |
|---|---|---|---|---|---|
| `GET` | `/api/v1/organizations/{orgId}/members` | `MEMBER_READ` | `200`, paginado | — | 1 |
| `POST` | `/api/v1/organizations/{orgId}/members` | `MEMBER_MANAGE_BASIC` o `_PRIVILEGED`, según el rol asignado | `201` | `400`, `403`, `404` (email sin cuenta), `409` (ya es miembro), `422 quota-exceeded` | 1 |
| `PATCH` | `/api/v1/organizations/{orgId}/members/{userId}` | Según el rol de origen y el de destino | `200` | `403`, `409 business-rule-violation` (último `OWNER`), `412` | 1 |
| `DELETE` | `/api/v1/organizations/{orgId}/members/{userId}` | Ídem, o el propio usuario (abandonar) | `204` | `403`, `409` (último `OWNER`) | 1 |

```jsonc
// POST /api/v1/organizations/{orgId}/members
{ "email": "luis@example.com", "role": "VIEWER" }
// 201
{ "userId": "0192…", "email": "luis@example.com", "displayName": "Luis", "role": "VIEWER", "joinedAt": "…" }
```

V1 solo añade usuarios que ya tienen cuenta. El `404` revela si el email está registrado a quien tiene permiso de gestionar miembros: es un riesgo aceptado hasta que las invitaciones de la Fase 5 lo sustituyan ([threat model](../security/threat-model.md), T-06).

## Proyectos

| Método | Ruta | Permiso | Éxito | Errores específicos | Fase |
|---|---|---|---|---|---|
| `POST` | `/api/v1/organizations/{orgId}/projects` | `PROJECT_WRITE` | `201` | `400`, `403`, `409` (nombre duplicado), `422 quota-exceeded` | 2 |
| `GET` | `/api/v1/organizations/{orgId}/projects` | `PROJECT_READ` | `200`, paginado. `sort`: `name`, `createdAt` | — | 2 |
| `GET` | `/api/v1/projects/{projectId}` | `PROJECT_READ` | `200` (incluye un resumen: monitores por estado) | — | 2 |
| `PATCH` | `/api/v1/projects/{projectId}` | `PROJECT_WRITE` | `200` | `400`, `403`, `409`, `412` | 2 |
| `DELETE` | `/api/v1/projects/{projectId}` | `PROJECT_WRITE` | `204` (borra sus monitores de forma asíncrona) | `403` | 2 |

```jsonc
// POST /api/v1/organizations/{orgId}/projects
{ "name": "Production", "description": "Servicios en producción" }
// GET /api/v1/projects/{projectId}
{
  "id": "0192…", "organizationId": "0192…", "name": "Production", "description": "…",
  "monitorCounts": { "UP": 3, "DEGRADED": 0, "DOWN": 1, "PENDING": 0, "PAUSED": 0 },
  "createdAt": "…", "version": 2
}
```

## Monitores (`monitoring`)

| Método | Ruta | Permiso | Éxito | Errores específicos | Fase |
|---|---|---|---|---|---|
| `POST` | `/api/v1/projects/{projectId}/monitors` | `MONITOR_WRITE` | `201` | `400`, `403`, `409` (nombre duplicado), `422 target-not-allowed`, `422 quota-exceeded` | 2 |
| `GET` | `/api/v1/projects/{projectId}/monitors` | `MONITOR_READ` | `200`, paginado. Filtros `status`, `enabled`, `q`. `sort`: `name`, `status`, `createdAt` | `400` | 2 |
| `GET` | `/api/v1/monitors/{monitorId}` | `MONITOR_READ` | `200` con `ETag` | — | 2 |
| `PATCH` | `/api/v1/monitors/{monitorId}` | `MONITOR_WRITE` | `200` | `400`, `403`, `409`, `412`, `422 target-not-allowed` | 2 |
| `POST` | `/api/v1/monitors/{monitorId}/pause` | `MONITOR_WRITE` | `200` (resuelve el incidente activo) | `403`, `409` (ya pausado) | 2 |
| `POST` | `/api/v1/monitors/{monitorId}/resume` | `MONITOR_WRITE` | `200` | `403`, `409` (no estaba pausado) | 2 |
| `DELETE` | `/api/v1/monitors/{monitorId}` | `MONITOR_WRITE` | `204` | `403` | 2 |

```jsonc
// POST /api/v1/projects/{projectId}/monitors
{
  "name": "Payments API",
  "url": "https://api.example.com/health",
  "httpMethod": "GET",                          // GET | HEAD, por defecto GET
  "expectedStatus": { "min": 200, "max": 299 }, // por defecto 200–299
  "intervalSeconds": 60,                         // 30–3600, por defecto 60
  "timeoutMs": 10000,                            // 1000–30000 y < interval, por defecto 10000
  "degradedThresholdMs": 2000,                   // opcional
  "followRedirects": true,                       // por defecto true
  "failureThreshold": 3,                         // 1–10, por defecto 3
  "recoveryThreshold": 2,                        // 1–10, por defecto 2
  "headers": [ { "name": "Authorization", "value": "Bearer s3cr3t" } ],   // máximo 10, solo escritura
  "enabled": true
}

// 201, GET /api/v1/monitors/{monitorId}
{
  "id": "0192…", "projectId": "0192…", "organizationId": "0192…",
  "name": "Payments API", "url": "https://api.example.com/health",
  "httpMethod": "GET", "expectedStatus": { "min": 200, "max": 299 },
  "intervalSeconds": 60, "timeoutMs": 10000, "degradedThresholdMs": 2000,
  "followRedirects": true, "failureThreshold": 3, "recoveryThreshold": 2,
  "headers": [ { "name": "Authorization", "value": null, "hasValue": true } ],
  "enabled": true,
  "state": {
    "status": "UP", "statusChangedAt": "…",
    "lastCheckedAt": "…", "lastResponseTimeMs": 143, "lastHttpStatus": 200,
    "consecutiveFailures": 0, "nextCheckAt": "…"
  },
  "createdAt": "…", "updatedAt": "…", "version": 1
}
```

Validaciones: ver el [modelo de dominio](../architecture/domain-model.md#monitor) y la [política SSRF](../security/ssrf-protection.md#capa-1-validación-al-guardar). En `PATCH`, `headers` reemplaza la lista entera si viene en el cuerpo.

## Checks y estadísticas

| Método | Ruta | Permiso | Éxito | Errores específicos | Fase |
|---|---|---|---|---|---|
| `GET` | `/api/v1/monitors/{monitorId}/checks` | `MONITOR_READ` | `200` con cursor. Filtros `status`, `from`, `to`. `limit` de 50 por defecto y 200 como máximo | `400` (cursor o rango inválido) | 3 |
| `GET` | `/api/v1/monitors/{monitorId}/stats` | `MONITOR_READ` | `200`. `window` = `24h`, `7d` o `30d` | `400` | 3 |

```jsonc
// GET /api/v1/monitors/{monitorId}/checks?limit=2
{
  "items": [
    { "checkedAt": "2026-09-28T10:03:00Z", "status": "DOWN", "httpStatus": null,
      "responseTimeMs": null, "failureReason": "TIMEOUT", "errorDetail": "no response within 10000 ms" },
    { "checkedAt": "2026-09-28T10:02:00Z", "status": "UP", "httpStatus": 200,
      "responseTimeMs": 143, "failureReason": null, "errorDetail": null }
  ],
  "nextCursor": "eyJ…"
}

// GET /api/v1/monitors/{monitorId}/stats?window=24h
{
  "window": "24h", "from": "…", "to": "…",
  "totalChecks": 1440, "up": 1428, "degraded": 5, "down": 7,
  "uptimePercent": 99.514,
  "responseTimeMs": { "avg": 151, "p50": 138, "p95": 290, "p99": 610 },
  "failuresByReason": { "TIMEOUT": 5, "UNEXPECTED_STATUS": 2 }
}
```

`uptimePercent` = `(up + degraded) / totalChecks × 100` sobre los checks de la ventana, con 3 decimales. Si no hay checks, `null`. Los percentiles solo cuentan los checks con respuesta. La definición está en el [glosario](../architecture/domain-model.md#1-glosario).

## Incidentes (`incident`)

| Método | Ruta | Permiso | Éxito | Errores específicos | Fase |
|---|---|---|---|---|---|
| `GET` | `/api/v1/organizations/{orgId}/incidents` | `INCIDENT_READ` | `200`, paginado. Filtros `status`, `projectId`, `monitorId`, `from`, `to`. `sort`: `openedAt`, `resolvedAt` | `400` | 4 |
| `GET` | `/api/v1/incidents/{incidentId}` | `INCIDENT_READ` | `200`, con el timeline | — | 4 |
| `POST` | `/api/v1/incidents/{incidentId}/acknowledge` | `INCIDENT_ACKNOWLEDGE` | `200` | `403`, `409 business-rule-violation` (no está `OPEN`), `409 concurrent-modification` | 4 |

No hay endpoint de resolución manual en V1 ([por qué](../architecture/incident-lifecycle.md#por-qué-no-hay-resolución-manual-r6)).

```jsonc
// POST /api/v1/incidents/{incidentId}/acknowledge
{ "note": "Investigando con el proveedor de pagos" }   // opcional, máximo 500

// GET /api/v1/incidents/{incidentId}
{
  "id": "0192…", "organizationId": "0192…", "projectId": "0192…",
  "monitorId": "0192…", "monitorName": "Payments API",
  "status": "ACKNOWLEDGED", "cause": "TIMEOUT", "causeHttpStatus": null,
  "openedAt": "2026-09-28T10:03:00Z",
  "acknowledgedAt": "2026-09-28T10:06:00Z", "acknowledgedBy": { "id": "0192…", "displayName": "Ana" },
  "resolvedAt": null, "resolution": null, "durationSeconds": null,
  "timeline": [
    { "type": "OPENED", "occurredAt": "2026-09-28T10:03:00Z", "actor": null, "note": null },
    { "type": "ACKNOWLEDGED", "occurredAt": "2026-09-28T10:06:00Z",
      "actor": { "id": "0192…", "displayName": "Ana" }, "note": "Investigando con el proveedor de pagos" }
  ],
  "version": 1
}
```

## Canales de notificación (`notification`)

| Método | Ruta | Permiso | Éxito | Errores específicos | Fase |
|---|---|---|---|---|---|
| `POST` | `/api/v1/organizations/{orgId}/notification-channels` | `CHANNEL_WRITE` | `201` (el webhook devuelve `signingSecret` **una sola vez**) | `400`, `403`, `422 target-not-allowed`, `422 quota-exceeded` | 4 |
| `GET` | `/api/v1/organizations/{orgId}/notification-channels` | `CHANNEL_READ` | `200`, paginado (configuración enmascarada) | — | 4 |
| `GET` | `/api/v1/notification-channels/{channelId}` | `CHANNEL_READ` | `200` | — | 4 |
| `PATCH` | `/api/v1/notification-channels/{channelId}` | `CHANNEL_WRITE` | `200` | `400`, `403`, `412`, `422` | 4 |
| `DELETE` | `/api/v1/notification-channels/{channelId}` | `CHANNEL_WRITE` | `204` | `403` | 4 |
| `POST` | `/api/v1/notification-channels/{channelId}/test` | `CHANNEL_WRITE` | `202` (envío asíncrono) | `403`, `429` (5 por minuto por canal) | 4 |
| `POST` | `/api/v1/notification-channels/{channelId}/rotate-secret` | `CHANNEL_WRITE` | `200` con el secreto nuevo, una sola vez | `403`, `409` (no es un webhook) | 4 |
| `GET` | `/api/v1/notification-channels/{channelId}/deliveries` | `CHANNEL_READ` | `200`, paginado (estado de las últimas entregas) | — | 4 |

```jsonc
// POST /api/v1/organizations/{orgId}/notification-channels (EMAIL)
{ "name": "Guardia", "type": "EMAIL", "projectId": null, "email": { "recipients": ["oncall@example.com"] } }

// POST (WEBHOOK)
{ "name": "Slack bridge", "type": "WEBHOOK", "projectId": "0192…", "webhook": { "url": "https://hooks.example.com/opswatch" } }
// 201
{ "id": "0192…", "type": "WEBHOOK", "webhook": { "url": "https://hooks.example.com/…", "signingSecret": "whsec_…" } }
```

Cuerpo que recibe un webhook (`POST`, `Content-Type: application/json`, header `X-OpsWatch-Signature`):

```json
{
  "id": "0192…",
  "type": "INCIDENT_OPENED",
  "occurredAt": "2026-09-28T10:03:00Z",
  "incident": {
    "id": "0192…", "status": "OPEN", "monitorId": "0192…", "monitorName": "Payments API",
    "projectId": "0192…", "cause": "TIMEOUT", "openedAt": "2026-09-28T10:03:00Z"
  }
}
```

## Operación (no forma parte de `/api/v1`)

| Ruta | Puerto | Acceso |
|---|---|---|
| `/actuator/health/liveness`, `/actuator/health/readiness` | 8081 (management) | Red interna y healthcheck de Docker |
| `/actuator/prometheus` | 8081 | Red interna (Prometheus en la Fase 7) |
| `/v3/api-docs`, `/swagger-ui.html` | 8080 | `local` y `staging` |

## Endpoints futuros (fuera de V1)

| Ruta | Fase |
|---|---|
| `POST /api/v1/auth/verify-email`, `POST /api/v1/auth/password-reset`, `POST /api/v1/auth/password-reset/confirm` | 5 |
| `POST /api/v1/organizations/{orgId}/invitations`, `POST /api/v1/invitations/{token}/accept` | 5 |
| `GET /api/v1/organizations/{orgId}/audit-log` | 5 |
| `GET /api/v1/public/status-pages/{slug}` | 8 |
| Stream de eventos en tiempo real (SSE o WebSocket) | 8 ([ADR-013](../adr/ADR-013-realtime-transport.md)) |
