# Guía de diseño de la API

Estado: diseño inicial · Última revisión: 2026-09-28 · Catálogo: [endpoints-v1.md](endpoints-v1.md)

## 1. Principios

- **REST sobre recursos** con JSON. Las acciones que no encajan en CRUD son subrecursos de acción (`POST /api/v1/monitors/{id}/pause`).
- **Nunca se exponen entidades JPA.** Cada endpoint tiene sus DTOs de request y de response.
- **Consistencia antes que ingenio:** los mismos nombres, errores, paginación y fechas en todos los endpoints.
- **Seguro por defecto:** autenticación obligatoria salvo la lista pública y autorización por organización en cada recurso.

## 2. URL y versionado

- Prefijo: `/api/v1`.
- **En la documentación, las issues y el código de ejemplo, las rutas se escriben siempre completas** (`POST /api/v1/auth/login`, nunca `POST /auth/login`), para que no haya dos nombres para el mismo endpoint.
- La versión va en la ruta: es visible, fácil de enrutar en un proxy y fácil de documentar. Spring Framework 7 incluye soporte nativo de versionado de API (`ApiVersionConfigurer`: por ruta, header o parámetro). No se activa mientras exista una sola versión: `/api/v1` es un prefijo fijo. Se reconsidera si llega una `v2`.
- Una versión nueva (`/api/v2`) **solo** para cambios incompatibles. Los cambios compatibles se añaden a `v1` ([versionado](../development/versioning.md#api)).
- Recursos en plural y en kebab-case: `/api/v1/notification-channels`.
- **Colecciones anidadas bajo su padre y elementos por id plano:**

| Operación | Ruta | Por qué |
|---|---|---|
| Listar o crear dentro de un padre | `GET` o `POST /api/v1/projects/{projectId}/monitors` | El padre define el ámbito y es lo que se autoriza |
| Leer, modificar o borrar un elemento | `GET`, `PATCH` o `DELETE /api/v1/monitors/{monitorId}` | El elemento ya sabe a qué organización pertenece. Anidar (`/api/v1/organizations/{o}/projects/{p}/monitors/{m}`) invitaría a confiar en ids de la ruta que podrían no ser coherentes entre sí |

- Ids: UUID en formato canónico en minúsculas.

## 3. Métodos

| Método | Uso | Idempotente |
|---|---|---|
| `GET` | Leer | Sí |
| `POST` | Crear, o ejecutar una acción | No |
| `PATCH` | Modificación parcial: los campos ausentes no cambian | Sí, para un mismo cuerpo |
| `DELETE` | Borrar (lógico o físico según el recurso) | Sí: borrar lo ya borrado da `404` sin efectos |

No se usa `PUT`: ningún recurso de V1 necesita un reemplazo completo y `PATCH` con DTOs explícitos cubre la edición.

**Semántica de `PATCH`:** el DTO tiene todos los campos opcionales. Un campo **ausente** no cambia. Un campo presente con `null` solo es válido si el campo admite nulo (por ejemplo, `degradedThresholdMs: null` desactiva el estado degradado). Las listas (`headers`) se reemplazan enteras si vienen en el cuerpo.

En el código, un campo que no admite nulo lleva `@JsonDeserialize(using = NotNullIfPresent.class)`: ausente queda `null` y no cambia, y un `null` explícito da `400 malformed-request`. `@JsonSetter(nulls = Nulls.FAIL)` no sirve en un record, porque Jackson también lo aplica al campo ausente. Un campo que admite nulo (`description` de un proyecto, `degradedThresholdMs` de un monitor) usa un tipo de tres estados de `shared.web` (ausente, `null` o valor), que llega con OW-019.

## 4. Códigos de estado

| Código | Cuándo |
|---|---|
| `200 OK` | Lectura, `PATCH` o acción con cuerpo de respuesta |
| `201 Created` | Creación. Incluye el header `Location` y el recurso en el cuerpo |
| `204 No Content` | `DELETE`, logout |
| `400 Bad Request` | JSON malformado, validación fallida, propiedad desconocida, parámetro inválido |
| `401 Unauthorized` | Sin credenciales o credenciales inválidas. Con `WWW-Authenticate` |
| `403 Forbidden` | Miembro de la organización sin el permiso necesario |
| `404 Not Found` | El recurso no existe, está borrado **o el usuario no es miembro de su organización** ([autorización](../security/authorization-model.md#por-qué-404-y-no-403-para-quien-no-es-miembro)) |
| `405 Method Not Allowed` | La ruta existe, pero no admite ese método |
| `406 Not Acceptable` | El cliente pide (`Accept`) un formato que la API no produce |
| `409 Conflict` | Conflicto de estado: regla de negocio (último `OWNER`, acknowledge sobre un incidente resuelto), duplicado (nombre de proyecto), bloqueo optimista |
| `412 Precondition Failed` | `If-Match` no coincide con la versión actual |
| `415 Unsupported Media Type` | El cuerpo no es `application/json` |
| `422 Unprocessable Content` | Petición bien formada pero semánticamente inaceptable: destino bloqueado por la política SSRF, cuota agotada |
| `429 Too Many Requests` | Rate limit. Con `Retry-After` |
| `500 Internal Server Error` | Error no previsto. Mensaje genérico y `requestId` |
| `503 Service Unavailable` | Dependencia caída (base de datos) |

## 5. Headers

| Header | Dirección | Uso |
|---|---|---|
| `Authorization: Bearer <jwt>` | Request | Autenticación |
| `Content-Type: application/json` | Ambas | Obligatorio en los requests con cuerpo |
| `X-Request-Id` | Ambas | Si el cliente lo envía y es válido (hasta 64 caracteres `[A-Za-z0-9-_]`), se reutiliza. Si no, se genera uno. Se devuelve siempre y aparece en los logs y los errores |
| `ETag` / `If-Match` | Response / Request | Versión del recurso (`"<version>"`) en los recursos editables (`ETags`). `If-Match` es **opcional**: si se envía y no coincide, `412`. Acepta `*` y listas; la comparación es fuerte, así que una etiqueta débil (`W/"3"`) no coincide nunca. Sin él, el `@Version` de JPA sigue protegiendo contra escrituras concurrentes (`409`) |
| `Location` | Response | URL del recurso creado |
| `Retry-After` | Response | En `429` y `503` |

## 6. DTOs

- Son `record` de Java.
- Nombres:

| Tipo | Nombre | Ejemplo |
|---|---|---|
| Crear | `Create<Recurso>Request` | `CreateMonitorRequest` |
| Modificar | `Update<Recurso>Request` | `UpdateMonitorRequest` |
| Acción | `<Acción><Recurso>Request` | `AcknowledgeIncidentRequest` |
| Respuesta completa | `<Recurso>Response` | `MonitorResponse` |
| Respuesta de listado | `<Recurso>SummaryResponse` | `MonitorSummaryResponse` |

- JSON en camelCase. Las fechas son cadenas ISO-8601 en UTC con `Z` (`2026-09-28T10:03:00Z`). Las duraciones son números con la unidad en el nombre (`timeoutMs`, `intervalSeconds`).
- Enums en `UPPER_SNAKE_CASE`.
- Los campos nulos se incluyen como `null`: el cliente ve siempre la misma forma.
- **Campos de solo escritura** (valores de headers, secretos): nunca en las respuestas. Se devuelve `"value": null` con `"hasValue": true`, o un valor enmascarado.
- Propiedades desconocidas en el request → `400`.

## 7. Validación

- Bean Validation en los DTOs: `@NotBlank`, `@Size`, `@Min`, `@Max`, `@Pattern` y validadores propios (`@ValidTargetUrl` para la forma; la política SSRF completa va en el servicio).
- Las reglas que dependen de más de un campo (`timeoutMs < intervalSeconds × 1000`) se validan en la entidad o el servicio y dan `400` si son de forma o `422` si son de negocio.
- Todos los errores de validación de un request se devuelven juntos, no uno a uno.

## 8. Formato de error: Problem Details (RFC 9457)

Todos los errores usan `application/problem+json` con el soporte `ProblemDetail` de Spring y dos extensiones: `code`, un código estable legible por máquina, y `requestId`.

```json
{
  "type": "https://github.com/RicardoOrd/opswatch/blob/main/docs/api/api-guidelines.md#validation-error",
  "title": "Validation failed",
  "status": 400,
  "detail": "The request contains 2 invalid fields.",
  "instance": "/api/v1/projects/0192b3c4-5d6e-7f80-9a1b-2c3d4e5f6a7b/monitors",
  "code": "validation-error",
  "requestId": "3f1c9a2e-8b7d-4c6e-a5f4-1b2c3d4e5f6a",
  "errors": [
    { "field": "intervalSeconds", "code": "range", "message": "must be between 30 and 3600" },
    { "field": "url", "code": "scheme", "message": "only http and https are allowed" }
  ]
}
```

- `type` apunta a la sección de este documento que explica el error. La URL base es configurable (`opswatch.api.problem-base-uri`) y el repositorio definitivo se fija al crearlo.
- `title` es fijo para cada `code`. `detail` explica el caso concreto **sin datos internos**: ni SQL, ni stack traces, ni nombres de clases.
- Los mensajes de la API van en inglés, porque son un contrato técnico. La documentación está en español.

### Catálogo de códigos

| `code` | HTTP | Cuándo |
|---|---|---|
| <a id="validation-error"></a>`validation-error` | 400 | Campos inválidos (con `errors[]`) |
| <a id="malformed-request"></a>`malformed-request` | 400 | JSON ilegible, tipo incorrecto, propiedad desconocida |
| <a id="invalid-parameter"></a>`invalid-parameter` | 400 | Parámetro de query inválido (`sort` no permitido, `size` > 100) |
| <a id="unauthenticated"></a>`unauthenticated` | 401 | Falta el token, es inválido o caducó |
| <a id="invalid-credentials"></a>`invalid-credentials` | 401 | Login fallido |
| <a id="access-denied"></a>`access-denied` | 403 | Miembro sin permiso |
| <a id="resource-not-found"></a>`resource-not-found` | 404 | No existe o no es visible para el usuario |
| <a id="method-not-allowed"></a>`method-not-allowed` | 405 | Método no admitido en esa ruta |
| <a id="not-acceptable"></a>`not-acceptable` | 406 | Formato de respuesta pedido no disponible |
| <a id="conflict"></a>`conflict` | 409 | Duplicado (nombre de proyecto, email registrado, miembro ya existente) |
| <a id="business-rule-violation"></a>`business-rule-violation` | 409 | Regla de negocio (último `OWNER`, transición de incidente inválida) |
| <a id="concurrent-modification"></a>`concurrent-modification` | 409 | Bloqueo optimista |
| <a id="precondition-failed"></a>`precondition-failed` | 412 | `If-Match` no coincide |
| <a id="unsupported-media-type"></a>`unsupported-media-type` | 415 | El cuerpo no es JSON |
| <a id="target-not-allowed"></a>`target-not-allowed` | 422 | La URL no pasa la política SSRF |
| <a id="quota-exceeded"></a>`quota-exceeded` | 422 | Se superó una cuota de la organización o del usuario |
| <a id="rate-limited"></a>`rate-limited` | 429 | Rate limit |
| <a id="internal-error"></a>`internal-error` | 500 | Error no previsto |
| <a id="service-unavailable"></a>`service-unavailable` | 503 | Dependencia no disponible |

## 9. Paginación

### Por offset: colecciones pequeñas o medianas

Se usa para organizaciones, miembros, proyectos, monitores, incidentes y canales.

```text
GET /api/v1/projects/{projectId}/monitors?page=0&size=20&sort=name,asc
```

```json
{
  "items": [ { "id": "…", "name": "Payments API", "status": "UP" } ],
  "page": { "number": 0, "size": 20, "totalElements": 42, "totalPages": 3 }
}
```

- `page` empieza en 0. `size` vale 20 por defecto y 100 como máximo. Por encima → `400 invalid-parameter`.
- La respuesta es un DTO propio (`PageResponse<T>`), **no** la serialización de `Page` de Spring Data, que no es un contrato estable.
- En el código, el controlador declara un parámetro `PageQuery` y llama a `toPageable(sortable, defaultSort)` con su lista blanca. `PageQueryArgumentResolver` lee `page`, `size` y cada `sort` tal como llegan: la conversión de Spring partiría un único `sort=name,asc` en dos valores. Los tres parámetros se documentan en OpenAPI con `@Parameter` en el método.

### Por cursor (keyset): series temporales

Se usa para `monitor_checks`, que puede tener cientos de miles de filas por monitor. `OFFSET` degrada de forma lineal.

```text
GET /api/v1/monitors/{monitorId}/checks?limit=50&cursor=eyJjIjoiMjAyNi0wOS0yOFQxMDowMzowMFoifQ
```

```json
{
  "items": [ { "checkedAt": "2026-09-28T10:03:00Z", "status": "DOWN", "failureReason": "TIMEOUT" } ],
  "nextCursor": "eyJjIjoiMjAyNi0wOS0yOFQxMDowMjowMFoifQ"
}
```

- Orden fijo: `checkedAt` descendente.
- `limit` vale 50 por defecto y 200 como máximo.
- El cursor es **opaco** (Base64URL de un JSON interno). El cliente no lo interpreta ni lo construye. Si está manipulado → `400`.
- `nextCursor` es `null` cuando no hay más.

## 10. Ordenación

- Parámetro `sort=campo,asc|desc`, repetible.
- **Lista blanca por endpoint**. Por ejemplo, monitores: `name`, `createdAt` y `status`. Un campo fuera de la lista → `400 invalid-parameter`, nunca se pasa a Spring Data sin filtrar.
- Siempre se añade un desempate por `id` para que la paginación sea estable.

## 11. Filtros

- Parámetros de query explícitos y documentados por endpoint: `?status=DOWN&q=payments`.
- Los valores múltiples se separan con comas: `?status=OPEN,ACKNOWLEDGED`.
- Rangos de tiempo: `from` y `to` en ISO-8601, `from` inclusivo y `to` exclusivo.
- `q` busca por prefijo sin distinguir mayúsculas en el campo de nombre. No hay búsqueda de texto completo en V1.
- No hay lenguajes de filtro genéricos (`filter=field:op:value`). Añaden superficie de ataque y complejidad sin necesidad.

## 12. OpenAPI

- Se genera desde el código con springdoc-openapi 3.1.1, compatible con Spring Boot 4.1.
- Swagger UI en `local` y `staging`. En `production` se desactiva por defecto: la especificación se publica como fichero en cada release.
- Todos los endpoints documentan sus respuestas de error con referencias a `ProblemDetail`.
- Más adelante (Fase 5), CI compara la especificación con la de `main` y marca los cambios incompatibles.

## 13. Deprecación

Cuando un endpoint vaya a retirarse: headers `Deprecation` y `Sunset` en sus respuestas, aviso en el changelog y al menos una release menor de margen antes de retirarlo en la siguiente versión mayor de la API.

## 14. Lo que no hay en V1

| Qué | Por qué no |
|---|---|
| `Idempotency-Key` en los `POST` | Ningún `POST` de V1 tiene efectos externos irreversibles. Se añadirá si llegan operaciones de pago o integraciones que lo requieran |
| HATEOAS | No hay un cliente que navegue por enlaces |
| GraphQL | Una API REST cubre los casos. No hay una variedad de clientes con necesidades distintas |
| Headers `RateLimit-*` | Solo `Retry-After` en `429`. Los headers estándar de cuota se añadirán cuando haya límites por usuario en toda la API |
