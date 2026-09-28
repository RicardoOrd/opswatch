# Definition of Done

Estado: diseño inicial · Última revisión: 2026-09-28

Una funcionalidad **no** está terminada porque compile, ni porque funcione en local. Está terminada cuando cumple todo lo que le aplica de esta lista, verificado en el PR.

## Global (todo cambio de código)

### Implementación
- [ ] Hace lo que pide la issue y sus criterios de aceptación.
- [ ] Respeta los límites de módulo (`verify()` en verde) o viene acompañada de un ADR que los cambie.
- [ ] Sigue los [estándares de código](../development/code-standards.md) (Spotless en verde, sin warnings).
- [ ] Sin código muerto, sin `TODO` sin issue asociada, sin logs de depuración olvidados.

### Tests
- [ ] **Unitarios** para la lógica de dominio nueva o cambiada.
- [ ] **Integración** con PostgreSQL real para lo que toque persistencia, consultas o bloqueos.
- [ ] **API** para los endpoints nuevos o cambiados: caso feliz, validación, `401`, `403`, `404` de otra organización y conflictos.
- [ ] Sin `Thread.sleep` ni dependencias del orden de los tests.
- [ ] `./mvnw verify` en verde en CI.

### Seguridad
- [ ] **Autorización:** todo recurso cargado por id pasa por `AccessControl`, y los endpoints nuevos están en la matriz de autorización.
- [ ] **Validación:** las entradas nuevas se validan en el DTO (forma) y en el dominio (reglas). Las propiedades desconocidas se rechazan.
- [ ] **Datos sensibles:** nada sensible en los logs, las respuestas ni los mensajes de error. Los secretos nuevos se cifran o se hashean.
- [ ] **Salida:** cualquier petición a una URL de usuario pasa por `egress`.
- [ ] El threat model se revisó si el cambio añade una interacción externa, un endpoint público o un activo nuevo.

### Errores y observabilidad
- [ ] Los errores nuevos usan Problem Details con un `code` del catálogo, o se añade el código al catálogo.
- [ ] Logs en el nivel adecuado, con el contexto (`requestId`, `monitorId`…) y sin ruido por operación.
- [ ] Métricas nuevas si el cambio introduce un proceso en segundo plano, una cola o una dependencia externa, sin etiquetas de alta cardinalidad.

### Datos
- [ ] Migración Flyway nueva (nunca se edita una aplicada) que cumple la [checklist de migraciones](../database/migrations.md#checklist-de-revisión-de-una-migración).
- [ ] Compatible con la versión desplegada anterior (expand / contract).
- [ ] `ddl-auto=validate` en verde.

### Documentación
- [ ] OpenAPI: los endpoints nuevos tienen descripción, ejemplos y respuestas de error.
- [ ] Los documentos de `docs/` afectados se actualizan **en el mismo PR** (catálogo de endpoints, modelo de dominio, catálogo de propiedades…).
- [ ] Lo implementado pasa de "Planned" a "Implemented" en el README si corresponde.
- [ ] ADR nuevo o actualizado si se tomó una decisión de arquitectura.

### Revisión y entrega
- [ ] PR con la plantilla completa (qué, por qué, cómo se probó, impacto en seguridad).
- [ ] Checklist de [revisión de código](../development/code-standards.md#revisión-de-código) repasada.
- [ ] CI en verde (build, tests, formato, gitleaks, Trivy).
- [ ] Merge con squash y un título en formato Conventional Commits.

## Añadidos por tipo de trabajo

| Tipo | Además de lo global |
|---|---|
| **Endpoint nuevo** | Fila en la matriz de autorización y test de IDOR |
| **Cambio de esquema** | `EXPLAIN` de las consultas afectadas con datos suficientes; plan expand / contract si no es compatible |
| **Evento nuevo** | Documentado en `events.md` (publicador, listeners, modo transaccional). Listener idempotente si es asíncrono |
| **Integración externa** (SMTP, webhook, servicio) | Timeouts explícitos, sin I/O dentro de transacciones, reintentos con backoff si procede y fallo simulado en los tests |
| **Corrección de seguridad** | Test que reproduce la vulnerabilidad antes de la corrección. Threat model actualizado |
| **Rendimiento** | Benchmark antes y después con el mismo procedimiento, y el resultado en `docs/performance/results/` |
| **Decisión de arquitectura** | ADR con las 10 preguntas y las decisiones abiertas actualizadas |
| **Release** | Tag SemVer, notas de release y, desde la Fase 6, despliegue en staging con los smoke tests en verde |

## Definition of Ready (antes de empezar una issue)

- [ ] Criterios de aceptación verificables.
- [ ] Dependencias terminadas o sin bloqueo.
- [ ] Consideraciones de seguridad identificadas.
- [ ] Si toca la arquitectura, existe un ADR o se sabe que hay que escribirlo.
- [ ] Cabe en una rama corta (días, no semanas). Si no, se parte.
