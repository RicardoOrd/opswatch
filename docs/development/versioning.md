# Versionado

Estado: diseño inicial · Última revisión: 2026-09-28

## Aplicación: Semantic Versioning

`MAJOR.MINOR.PATCH`, con tags `vX.Y.Z` sobre `main`.

| Cambio | Incrementa |
|---|---|
| Cambio incompatible en la API pública, en el contrato de webhooks o en la configuración obligatoria | MAJOR |
| Funcionalidad nueva compatible | MINOR |
| Corrección compatible | PATCH |

**Antes de 1.0.0** (Fases 1 a 5) la API no es estable y los cambios incompatibles suben la versión MINOR. Cada fase cerrada publica una versión:

| Versión | Hito |
|---|---|
| 0.1.0 | Fase 1: identity y organizaciones |
| 0.2.0 | Fase 2: proyectos y monitores |
| 0.3.0 | Fase 3: motor de monitoreo |
| 0.4.0 | Fase 4: incidentes y notificaciones |
| 0.5.0 | Fase 5: endurecimiento |
| **1.0.0** | Fase 6: V1 desplegada. A partir de aquí, la API `v1` es estable |

- Notas de release generadas a partir de los Conventional Commits (las que genera GitHub al crear la release), revisadas a mano.
- La versión se expone en `/actuator/info` y en el User-Agent del motor.

### Imágenes Docker

| Tag | Cuándo |
|---|---|
| `sha-<7 caracteres>` | Cada push a `main` |
| `main` | Último build de `main` (solo para staging manual) |
| `X.Y.Z` | Cada release |
| `X.Y` | Última patch de esa minor |

**No** se usa `latest`: producción siempre despliega una versión explícita.

## API

- La versión mayor va en la ruta: `/api/v1`.
- **Cambios compatibles** (se añaden a `v1` sin aviso): endpoints nuevos, campos nuevos **opcionales** en los requests, campos nuevos en las respuestas, valores nuevos en filtros.
- **Cambios incompatibles** (requieren `v2` o, antes de 1.0.0, una minor con nota): quitar o renombrar campos o endpoints, cambiar tipos o semántica, hacer obligatorio un campo opcional, cambiar códigos de estado o de error, **añadir un valor nuevo a un enum de respuesta**. Esto último se trata como incompatible por prudencia: muchos clientes fallan con valores desconocidos.
- Por eso, la documentación de la API pide a los clientes que toleren **campos** desconocidos en las respuestas. Los valores nuevos de enums se anuncian en el changelog.
- Retirada: headers `Deprecation` y `Sunset` y al menos una release minor de margen ([guía de API](../api/api-guidelines.md#13-deprecación)).
- Con `v2`, las dos versiones conviven el tiempo anunciado. Se implementan como controladores distintos sobre los mismos casos de uso.

### Webhooks

El cuerpo de los webhooks es un contrato público más, con su propio campo de versión en el header: `X-OpsWatch-Webhook-Version: 1`. Sigue las mismas reglas de compatibilidad que la API.

## Base de datos

- Versionada con Flyway (`V<n>__…`), un historial lineal ([migraciones](../database/migrations.md)).
- El esquema **no** sigue SemVer: evoluciona con migraciones compatibles hacia atrás (expand / contract), de modo que la versión N de la aplicación funciona con el esquema de la N+1 durante un despliegue o un rollback.
- En la Etapa 3, cada servicio tiene su propio historial.

## Eventos

**V1 (dentro del proceso):** los eventos son `record` de Java. El compilador y los tests protegen los contratos y no hace falta versionarlos.

**Cuando se distribuyan (Etapa 3 o 4):**

- Envelope común: `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `producer`, `payload`.
- **Cambios compatibles** en la misma `schemaVersion`: añadir campos opcionales. Los consumidores ignoran los campos desconocidos.
- **Cambios incompatibles:** `schemaVersion` nueva, y el productor publica las dos versiones durante una transición. Si cambia el significado, se usa un **tipo de evento nuevo** con un nombre que describa el hecho nuevo, no un sufijo `V2`.
- Los consumidores rechazan, a una cola de errores o registro, las versiones que no conocen, en lugar de procesarlas mal.
- Registro de esquemas: se decide junto con el broker ([ADR-010](../adr/ADR-010-event-broker.md)). Con JSON y pocos eventos, la documentación en el repositorio y tests de contrato bastan. Con Kafka y Avro, un registro de esquemas empieza a compensar.
- Tests de contrato: el productor publica ejemplos de cada evento como ficheros JSON versionados y los consumidores tienen tests que los deserializan.
