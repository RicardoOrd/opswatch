# Versionado

Estado: diseño inicial · Última revisión: 2026-10-05

## Aplicación: Semantic Versioning

`MAJOR.MINOR.PATCH`, con tags `vX.Y.Z` sobre `main`.

| Cambio | Incrementa |
|---|---|
| Cambio incompatible en la API pública, en el contrato de webhooks o en la configuración obligatoria | MAJOR |
| Funcionalidad nueva compatible | MINOR |
| Corrección compatible | PATCH |

**Antes de 1.0.0** (Fases 1 a 5) la API no es estable y los cambios incompatibles suben la versión MINOR. Cada fase cerrada publica una versión, y el número de la minor coincide con el de la fase:

| Versión | Hito | Milestone de GitHub |
|---|---|---|
| — | Fase 0: fundaciones. Sin release: no hay funcionalidad que versionar | Sprint 0 — Fundaciones |
| 0.1.0 | Fase 1: identity y organizaciones | v0.1.0 — Identity y organizaciones |
| 0.2.0 | Fase 2: proyectos y monitores | v0.2.0 — Proyectos y monitores |
| 0.3.0 | Fase 3: motor de monitoreo | v0.3.0 — Motor de monitoreo |
| 0.4.0 | Fase 4: incidentes y notificaciones | v0.4.0 — Incidentes y notificaciones |
| 0.5.0 | Fase 5: endurecimiento de seguridad | v0.5.0 — Endurecimiento de seguridad |
| **1.0.0** | Fase 6: V1 desplegada. A partir de aquí, la API `v1` es estable | v1.0.0 — V1 desplegada |

- La versión se expone en el User-Agent del motor. `/actuator/info` todavía no la da: el `build-info` del plugin de Spring Boot llega en la Fase 6, donde lo comprueban los smoke tests.
- **Versión del pom:** el commit que recibe el tag lleva `X.Y.Z`, y justo después `main` pasa a la siguiente minor con `-SNAPSHOT` (`X.(Y+1).0-SNAPSHOT`). Así el jar y la imagen de un tag se identifican con su versión, y un build de `main` entre releases no se confunde con uno publicado. Se aplica desde la 0.3.0: las releases 0.1.0 y 0.2.0 se publicaron con el pom en `0.1.0-SNAPSHOT`.
- Correcciones sobre una versión publicada, si hacen falta: `0.N.1`, `0.N.2`… sin milestone propio.

### Proceso de release

Una release **no** forma parte de ninguna issue funcional. Las issues se cierran cuando su trabajo está hecho, y la release es un paso aparte que cierra el milestone:

1. Todas las issues del milestone están cerradas (o movidas a otro milestone con un motivo escrito).
2. Se abre una issue con la plantilla **Release** (`Release vX.Y.Z`) en el mismo milestone, con esta checklist:
   - [ ] Criterios de aceptación de la fase comprobados sobre `main` ([roadmap](../roadmap/roadmap.md)).
   - [ ] CI en verde en el último commit de `main`.
   - [ ] README: lo publicado pasa de *Planned* a *Implemented*.
   - [ ] Documentación de la fase coherente con el código (catálogos de endpoints y de propiedades, modelo de dominio).
   - [ ] `pom.xml` en `X.Y.Z` en el PR de docs de cierre, que es el commit del tag.
   - [ ] Tag `vX.Y.Z` sobre `main` y release de GitHub con notas generadas a partir de los Conventional Commits, revisadas a mano.
   - [ ] `pom.xml` en `X.(Y+1).0-SNAPSHOT` en `main` justo después del tag.
   - [ ] Desde la Fase 6: despliegue en staging, smoke tests y promoción a producción.
3. Se cierra la issue de release y, con ella, el milestone.

Crear el tag es responsabilidad de esa issue de release y de nadie más.

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

El cuerpo de los webhooks es un contrato público más, con su propio campo de versión en el header: `X-OpsWatch-Webhook-Version: 1`. Sigue las mismas reglas de compatibilidad que la API. Qué contiene la versión `1`: [guía para receptores](../api/webhooks.md).

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
