# Catálogo de endpoints `/api/v1`

Estado: diseño inicial · Última revisión: 2026-10-02 · Convenciones: [api-guidelines.md](api-guidelines.md) · Permisos: [authorization-model.md](../security/authorization-model.md)

**Existen `POST /api/v1/auth/register` (OW-012), `POST /api/v1/auth/login` y `GET /api/v1/me` (OW-013), y `POST /api/v1/auth/refresh` y `POST /api/v1/auth/logout` (OW-014), con rate limiting desde OW-015, `PATCH /api/v1/me` y `POST /api/v1/me/password` (OW-045), los cinco endpoints de organizaciones (OW-016), los cuatro de miembros (OW-017) y los cinco de proyectos (OW-019). El resto está planificado.** La columna "Fase" indica cuándo se implementa cada uno. Los errores comunes a todos los endpoints autenticados (`401`, `404` a quien no es miembro, `429` y `500`) no se repiten en cada tabla.

## Autenticación (`identity`)

| Método | Ruta | Autenticación | Éxito | Errores específicos | Fase |
|---|---|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Pública | `201` (no inicia sesión) | `400`, `409 conflict` (email registrado), `429 rate-limited` (más de 5 por hora desde una IP) | 1 |
| `POST` | `/api/v1/auth/login` | Pública | `200` y cookie de refresh | `400`, `401 invalid-credentials` (el mismo cuerpo exista o no el email), `429 rate-limited` (más de 10 por minuto desde una IP o más de 5 contra un email) | 1 |
| `POST` | `/api/v1/auth/refresh` | Cookie | `200` y cookie nueva | `401 unauthenticated` (token ausente, desconocido, caducado, revocado o reutilizado, o cuenta deshabilitada), `403 access-denied` (`Origin` ausente o no permitido), `415` (sin `Content-Type: application/json`), `429 rate-limited` (más de 30 por minuto desde una IP) | 1 |
| `POST` | `/api/v1/auth/logout` | Cookie | `204` y cookie borrada, también sin cookie o con un token desconocido | `403 access-denied` (`Origin` ausente o no permitido), `415` | 1 |
| `GET` | `/api/v1/me` | JWT | `200` y `ETag` | `401` (token ausente, inválido o caducado) | 1 |
| `PATCH` | `/api/v1/me` | JWT | `200` con la cuenta y su `ETag` | `400` (`displayName` inválido o `null`; `email` u otra propiedad desconocida), `409`, `412` | 1 |
| `POST` | `/api/v1/me/password` | JWT | `204` (revoca todos los refresh tokens del usuario) | `400` (también con la contraseña actual incorrecta, como error `incorrect-password` de `currentPassword`), `409 concurrent-modification` (otro cambio de contraseña se adelantó), `429 rate-limited` (más de 5 intentos cada 15 minutos por usuario, desde cualquier IP) | 1 |

```jsonc
// POST /api/v1/auth/register
{ "email": "ana@example.com", "displayName": "Ana", "password": "correct horse battery" }
// 201, Location: /api/v1/me
{ "id": "0192…", "email": "ana@example.com", "displayName": "Ana", "createdAt": "2026-09-28T10:00:00Z" }

// POST /api/v1/auth/login
{ "email": "ana@example.com", "password": "correct horse battery" }
// 200, Set-Cookie: opswatch_refresh=…; Path=/api/v1/auth; Max-Age=1209600; Secure; HttpOnly; SameSite=Strict
{ "accessToken": "eyJ…", "tokenType": "Bearer", "expiresIn": 900 }

// POST /api/v1/auth/refresh, con Cookie: opswatch_refresh=…, Origin y Content-Type: application/json. El cuerpo se ignora
// 200, Set-Cookie con el token nuevo: el anterior deja de servir
{ "accessToken": "eyJ…", "tokenType": "Bearer", "expiresIn": 900 }

// POST /api/v1/auth/logout, con los mismos headers
// 204, Set-Cookie: opswatch_refresh=; Path=/api/v1/auth; Max-Age=0; …

// GET /api/v1/me
{ "id": "0192…", "email": "ana@example.com", "displayName": "Ana", "createdAt": "2026-09-28T10:00:00Z" }

// PATCH /api/v1/me
{ "displayName": "Ana García" }
// 200
{ "id": "0192…", "email": "ana@example.com", "displayName": "Ana García", "createdAt": "2026-09-28T10:00:00Z" }

// POST /api/v1/me/password
{ "currentPassword": "correct horse battery", "newPassword": "another long passphrase" }
// 204
```

Validaciones: `email` con formato válido, solo ASCII imprimible y hasta 254 caracteres, normalizado a minúsculas. `displayName` de 1 a 100 caracteres, sin caracteres de control, separadores de línea ni de formato bidireccional. `password` y `newPassword` de 12 caracteres a 72 bytes UTF-8, con el código de error `password-policy`. Una `currentPassword` que no es la actual da el código `incorrect-password` en ese campo. Los espacios alrededor del email y del nombre se quitan; los de la contraseña, no.

- El access token (RS256, 15 minutos) solo identifica al usuario: no lleva roles ni organizaciones ([ADR-004](../adr/ADR-004-security-strategy.md)). Un token rechazado da `401` con `WWW-Authenticate: Bearer error="invalid_token"`, sin decir por qué.
- Un email inexistente, una contraseña incorrecta y una cuenta deshabilitada dan el mismo `401 invalid-credentials`, y tardan lo mismo: el email inexistente se compara con un hash señuelo del mismo coste.
- El refresh token rota en cada uso. Presentar uno ya gastado revoca la sesión entera, también el token más reciente: dos pestañas que refrescan a la vez con la misma cookie cierran la sesión ([ADR-004](../adr/ADR-004-security-strategy.md)). El cliente debe serializar sus refresh.
- `refresh` y `logout` solo aceptan un `Origin` igual al del `issuer` de los JWT o a uno de `opswatch.security.cors.allowed-origins`, y solo JSON: defensa contra CSRF además de `SameSite=Strict` ([arquitectura de seguridad](../security/security-architecture.md#csrf)).
- `GET /api/v1/me` no incluye las organizaciones del usuario: `identity` no puede depender de `organization` ([módulos](../architecture/modules.md)). Salen, con el rol del usuario en cada una, en `GET /api/v1/organizations`.
- La contraseña actual incorrecta en `POST /api/v1/me/password` da `400` y no `401`: el access token es válido, y un `401` haría que el cliente intentara refrescarlo.
- El cambio de contraseña revoca todas las sesiones del usuario, también la de quien lo hace: su refresh token da `401` y tiene que volver a iniciar sesión. Los access tokens ya emitidos siguen valiendo hasta caducar ([ADR-004](../adr/ADR-004-security-strategy.md)).
- `PATCH /api/v1/me` solo edita `displayName`. El email no cambia en V1, porque cambiarlo exige verificarlo (OW-037): un `email` en el cuerpo es una propiedad desconocida y da `400`. `GET` y `PATCH /api/v1/me` devuelven el `ETag` de la cuenta, y `PATCH` acepta `If-Match` (OW-016).

## Organizaciones (`organization`)

| Método | Ruta | Permiso | Éxito | Errores específicos | Fase |
|---|---|---|---|---|---|
| `POST` | `/api/v1/organizations` | Autenticado | `201` y `ETag` (quien la crea queda como `OWNER`) | `400`, `422 quota-exceeded` (ya es `OWNER` de 5 organizaciones no borradas) | 1 |
| `GET` | `/api/v1/organizations` | Autenticado | `200`, paginado (solo aquellas de las que es miembro, con su rol). `sort` por `name` (por defecto) o `createdAt` | `400 invalid-parameter` | 1 |
| `GET` | `/api/v1/organizations/{orgId}` | `ORGANIZATION_READ` | `200` y `ETag` | — | 1 |
| `PATCH` | `/api/v1/organizations/{orgId}` | `ORGANIZATION_UPDATE` | `200` y `ETag` nuevo | `400` (`name` inválido o `null`), `403`, `409`, `412` | 1 |
| `DELETE` | `/api/v1/organizations/{orgId}` | `ORGANIZATION_DELETE` | `204` | `403` | 1 |

```jsonc
// POST /api/v1/organizations
{ "name": "CharityLink" }
// 201, Location: /api/v1/organizations/0192…, ETag: "0"
{ "id": "0192…", "name": "CharityLink", "myRole": "OWNER", "createdAt": "…", "version": 0 }

// GET /api/v1/organizations?page=0&size=20&sort=name,asc
{
  "items": [ { "id": "0192…", "name": "CharityLink", "myRole": "OWNER", "createdAt": "…" } ],
  "page": { "number": 0, "size": 20, "totalElements": 1, "totalPages": 1 }
}

// PATCH /api/v1/organizations/{orgId}, con If-Match: "0" (opcional)
{ "name": "Charity Link" }
// 200, ETag: "1"
{ "id": "0192…", "name": "Charity Link", "myRole": "OWNER", "createdAt": "…", "version": 1 }
```

- Quien no es miembro recibe `404` en cualquier endpoint con `{orgId}`, igual que con un id inexistente o una organización borrada.
- Borrar es lógico y publica `OrganizationDeleted`. La organización desaparece para todos sus miembros, también del listado, y deja de contar en la cuota de su `OWNER`.
- Validación de `name`: de 1 a 100 caracteres, sin caracteres de control, separadores de línea ni de formato bidireccional, y sin los espacios alrededor. No tiene que ser único.

## Miembros

| Método | Ruta | Permiso | Éxito | Errores específicos | Fase |
|---|---|---|---|---|---|
| `GET` | `/api/v1/organizations/{orgId}/members` | `MEMBER_READ` | `200`, paginado. `sort` por `joinedAt` (por defecto, ascendente) | `400 invalid-parameter` | 1 |
| `POST` | `/api/v1/organizations/{orgId}/members` | `MEMBER_MANAGE_BASIC` o `_PRIVILEGED`, según el rol asignado | `201` y `ETag` | `400`, `403`, `404` (email sin cuenta), `409 conflict` (ya es miembro), `422 quota-exceeded` (50 miembros) | 1 |
| `PATCH` | `/api/v1/organizations/{orgId}/members/{userId}` | Según el rol de origen y el de destino; bajarse el propio rol no necesita permiso | `200` y `ETag` nuevo | `400`, `403` (también al subirse el propio rol), `404` (no es miembro), `409 business-rule-violation` (último `OWNER`), `412` | 1 |
| `DELETE` | `/api/v1/organizations/{orgId}/members/{userId}` | Ídem, o el propio usuario (abandonar) | `204` | `403`, `404` (no es miembro), `409` (último `OWNER`) | 1 |

```jsonc
// POST /api/v1/organizations/{orgId}/members
{ "email": "luis@example.com", "role": "VIEWER" }
// 201, Location: /api/v1/organizations/{orgId}/members/0192…, ETag: "0"
{ "userId": "0192…", "email": "luis@example.com", "displayName": "Luis", "role": "VIEWER", "joinedAt": "…", "version": 0 }

// PATCH /api/v1/organizations/{orgId}/members/{userId}, con If-Match: "0" (opcional)
{ "role": "MEMBER" }
// 200, ETag: "1"
```

- Tocar `OWNER` o `ADMIN`, antes o después del cambio, exige `MEMBER_MANAGE_PRIVILEGED` (solo `OWNER`). Entre `MEMBER` y `VIEWER` basta `MEMBER_MANAGE_BASIC` (`OWNER` y `ADMIN`).
- Degradar o expulsar al único `OWNER` da `409` sea quien sea quien lo pida: la regla se comprueba antes que el permiso ([modelo de autorización](../security/authorization-model.md#reglas-que-la-matriz-no-expresa)).
- El permiso se comprueba antes de buscar el email: solo quien puede añadir miembros llega a saber si está registrado.

V1 solo añade usuarios que ya tienen cuenta. El `404` revela si el email está registrado a quien tiene permiso de gestionar miembros: es un riesgo aceptado hasta que las invitaciones de la Fase 5 lo sustituyan ([threat model](../security/threat-model.md), T-06).

## Proyectos

| Método | Ruta | Permiso | Éxito | Errores específicos | Fase |
|---|---|---|---|---|---|
| `POST` | `/api/v1/organizations/{orgId}/projects` | `PROJECT_WRITE` | `201` y `ETag` | `400`, `403`, `409` (nombre duplicado), `422 quota-exceeded` (20 proyectos) | 2 |
| `GET` | `/api/v1/organizations/{orgId}/projects` | `PROJECT_READ` | `200`, paginado. `sort` por `name` (por defecto) o `createdAt` | `400 invalid-parameter` | 2 |
| `GET` | `/api/v1/projects/{projectId}` | `PROJECT_READ` | `200` y `ETag` | — | 2 |
| `PATCH` | `/api/v1/projects/{projectId}` | `PROJECT_WRITE` | `200` y `ETag` nuevo | `400`, `403`, `409` (nombre duplicado o cambio concurrente), `412` | 2 |
| `DELETE` | `/api/v1/projects/{projectId}` | `PROJECT_WRITE` | `204` (borra sus monitores de forma asíncrona) | `403` | 2 |

```jsonc
// POST /api/v1/organizations/{orgId}/projects
{ "name": "Production", "description": "Servicios en producción" }
// GET /api/v1/projects/{projectId}, ETag: "2"
{
  "id": "0192…", "organizationId": "0192…", "name": "Production", "description": "…",
  "createdAt": "…", "updatedAt": "…", "version": 2
}

// PATCH /api/v1/projects/{projectId}, con If-Match: "2" (opcional)
{ "description": null }   // borra la descripción; sin el campo, se conserva
```

- Quien no es miembro de la organización del proyecto recibe `404` en los endpoints con `{projectId}`, igual que con un id inexistente o un proyecto borrado.
- `name`: de 1 a 100 caracteres, único en la organización sin distinguir mayúsculas (entre los no borrados), con las reglas de caracteres del nombre de la organización. `description`: opcional, hasta 500 caracteres, con las mismas reglas. En `PATCH`, `name: null` → `400` y `description: null` la borra.
- Borrar la organización borra sus proyectos en la misma transacción.
- Los monitores por estado de un proyecto los da `monitoring`: `GET /api/v1/projects/{projectId}/monitors/summary`. `organization` no puede depender de `monitoring` ([módulos](../architecture/modules.md#4-reglas-de-dependencia)).

## Monitores (`monitoring`)

| Método | Ruta | Permiso | Éxito | Errores específicos | Fase |
|---|---|---|---|---|---|
| `POST` | `/api/v1/projects/{projectId}/monitors` | `MONITOR_WRITE` | `201` y `ETag` | `400`, `403`, `409` (nombre duplicado), `422 target-not-allowed`, `422 quota-exceeded` (50 monitores por organización) | 2 |
| `GET` | `/api/v1/projects/{projectId}/monitors` | `MONITOR_READ` | `200`, paginado. Filtros `status` y `q` (nombre). `sort` por `name` (por defecto), `status` o `createdAt` | `400 invalid-parameter` | 2 |
| `GET` | `/api/v1/projects/{projectId}/monitors/summary` | `MONITOR_READ` | `200`: monitores del proyecto por estado | — | 2 |
| `GET` | `/api/v1/monitors/{monitorId}` | `MONITOR_READ` | `200` y `ETag` | — | 2 |
| `PATCH` | `/api/v1/monitors/{monitorId}` | `MONITOR_WRITE` | `200` y `ETag` nuevo | `400`, `403`, `409` (nombre duplicado o cambio concurrente), `412`, `422 target-not-allowed` | 2 |
| `POST` | `/api/v1/monitors/{monitorId}/pause` | `MONITOR_WRITE` | `200` con el monitor y su `ETag` (desde OW-032, resuelve el incidente activo) | `403`, `409` (ya pausado) | 2 |
| `POST` | `/api/v1/monitors/{monitorId}/resume` | `MONITOR_WRITE` | `200` con el monitor y su `ETag` | `403`, `409` (no estaba pausado) | 2 |
| `DELETE` | `/api/v1/monitors/{monitorId}` | `MONITOR_WRITE` | `204` | `403`, `409` (cambio concurrente) | 2 |

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
  "headers": [ { "name": "Authorization", "value": "Bearer s3cr3t" } ]    // máximo 10, solo escritura (OW-022)
}

// 201, GET /api/v1/monitors/{monitorId}
{
  "id": "0192…", "projectId": "0192…", "organizationId": "0192…",
  "name": "Payments API", "url": "https://api.example.com/health",
  "httpMethod": "GET", "expectedStatus": { "min": 200, "max": 299 },
  "intervalSeconds": 60, "timeoutMs": 10000, "degradedThresholdMs": 2000,
  "followRedirects": true, "failureThreshold": 3, "recoveryThreshold": 2,
  "headers": [ { "name": "Authorization", "value": null, "hasValue": true } ],
  "state": {
    "status": "UP", "statusChangedAt": "…",
    "lastCheckedAt": "…", "lastResponseTimeMs": 143, "lastHttpStatus": 200,
    "consecutiveFailures": 0, "nextCheckAt": "…"
  },
  "createdAt": "…", "updatedAt": "…", "version": 1
}
```

```jsonc
// GET /api/v1/projects/{projectId}/monitors/summary
{ "total": 4, "byStatus": { "PENDING": 0, "UP": 3, "DEGRADED": 0, "DOWN": 1, "PAUSED": 0 } }
```

Validaciones: ver el [modelo de dominio](../architecture/domain-model.md#monitor) y la [política SSRF](../security/ssrf-protection.md#capa-1-validación-al-guardar).

- La URL se guarda y se devuelve **normalizada** (esquema y host en minúsculas, host en punycode, sin fragmento).
- Un monitor nace en `PENDING`. No hay campo `enabled`: un monitor está pausado cuando su estado es `PAUSED`, y solo `pause` y `resume` lo cambian.
- Las invariantes (por ejemplo, `timeoutMs` menor que el intervalo) se comprueban sobre el estado resultante: un `PATCH` que solo cambia uno de los dos puede dar `400`. El error es un `validation-error` sobre el campo que la rompe, con los códigos `not-below-interval` (`timeoutMs`), `above-timeout` (`degradedThresholdMs`) y `min-above-max` (`expectedStatus`).
- En `POST`, un campo opcional ausente o `null` toma su valor por defecto. En `PATCH`, `degradedThresholdMs: null` desactiva el estado degradado; los demás campos no admiten `null`. `expectedStatus` cambia solo los extremos que vienen (`{"max": 204}` conserva `min`). `headers` reemplaza la lista entera si viene en el cuerpo, y `[]` los quita todos.
- Un `PATCH` con `url` la vuelve a validar con la política SSRF, aunque sea la misma.
- `q` busca en el nombre sin distinguir mayúsculas, de forma literal (`%`, `_` y `\` no son comodines ni escapes), con 100 caracteres como máximo. `status` filtra por estado (`PENDING`, `UP`, `DEGRADED`, `DOWN` o `PAUSED`, en mayúsculas). `sort=status` ordena por el código del estado en orden alfabético.
- Quien no es miembro recibe `404` con el detalle del monitor (`monitor <id> was not found`), nunca del proyecto. Un monitor de un proyecto borrado da `404` aunque su limpieza asíncrona (OW-044) todavía no haya terminado.
- El `422 target-not-allowed` dice qué regla falla, nunca las IP resueltas.
- `headers` (OW-022) es de solo escritura: las respuestas dan cada nombre con `"value": null` y `hasValue`, y ningún rol lee un valor. Se guardan cifrados ([cifrado](../security/security-architecture.md#cifrado-de-datos-sensibles-en-la-base-de-datos)). Los espacios alrededor de un valor se quitan. Un header que no cumple la [capa 4 de la política SSRF](../security/ssrf-protection.md#capa-4-restricciones-de-la-petición) da `400 validation-error` sobre su campo (`headers[1].name`, `headers[1].value` o `headers`), con los códigos `forbidden`, `invalid-name`, `invalid-value`, `duplicate`, `too-long` y `too-many`. El mensaje nunca repite el valor.

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

`uptimePercent` = `(up + degraded) / totalChecks × 100` sobre los checks de la ventana, con 3 decimales. Si no hay checks, `null`. Los percentiles solo cuentan los checks con respuesta, se redondean a milisegundos enteros y son `null` si ninguno la tuvo. La ventana termina ahora; sin `window`, `24h`. En el historial, `status` admite varios valores separados por comas, `from` es inclusivo y `to` exclusivo, y `from` que no es anterior a `to` da `400 invalid-parameter` (OW-028). La definición está en el [glosario](../architecture/domain-model.md#1-glosario).

## Incidentes (`incident`)

| Método | Ruta | Permiso | Éxito | Errores específicos | Fase |
|---|---|---|---|---|---|
| `GET` | `/api/v1/organizations/{orgId}/incidents` | `INCIDENT_READ` | `200`, paginado, sin el timeline. Filtros `status` (varios, separados por comas), `projectId`, `monitorId`, `from` (inclusivo) y `to` (exclusivo) sobre `openedAt`. `sort`: `openedAt` (por defecto, descendente) o `resolvedAt` | `400 invalid-parameter` (también si `from` no es anterior a `to`) | 4 |
| `GET` | `/api/v1/incidents/{incidentId}` | `INCIDENT_READ` | `200`, con el timeline | — | 4 |
| `POST` | `/api/v1/incidents/{incidentId}/acknowledge` | `INCIDENT_ACKNOWLEDGE` | `200` | `403`, `409 business-rule-violation` (no está `OPEN`, también si la recuperación lo resolvió a la vez) | 4 |

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

- La autorización va por la organización del incidente, no por su proyecto: los incidentes de un proyecto o un monitor borrados siguen visibles como historial (OW-033). Quien no es miembro recibe `404` con el detalle del incidente.
- El cuerpo del acknowledge es opcional. La nota es una línea de hasta 500 caracteres, sin caracteres de control; se devuelve tal cual, y quien la muestre en una página la escapa.
- `acknowledgedBy` y el `actor` de cada entrada del timeline dan el nombre que el usuario tiene hoy; son `null` para lo que hizo el sistema y cuando la cuenta ya no existe. `durationSeconds` va de `openedAt` a `resolvedAt` y es `null` mientras el incidente está activo.
- Ordenado por `resolvedAt` descendente, los incidentes activos (sin `resolvedAt`) salen primero.
- El acknowledge y la resolución automática se serializan sobre la fila del incidente ([concurrencia](../architecture/incident-lifecycle.md#6-concurrencia-y-casos-límite)): no hay `409 concurrent-modification` en este endpoint.

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

- `id` es el de la entrega: la entrega es at-least-once, y el receptor descarta los duplicados por él. `type` es `INCIDENT_OPENED`, `INCIDENT_RESOLVED` o `TEST`.
- OpsWatch no sigue redirects: un `3xx` cuenta como intento fallido, igual que cualquier respuesta fuera de `2xx` o que tarde más de 5 s (OW-043).
- `POST …/test` crea una entrega `TEST` que procesa el mismo worker que las reales, así que aparece en `…/deliveries` (OW-036).
- Un canal limitado a un proyecto se borra, con sus entregas, cuando se borra el proyecto (OW-035).

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
