# Definition of Done

Estado: diseño inicial · Última revisión: 2026-09-28

Una funcionalidad **no** está terminada porque compile, ni porque funcione en local. Tampoco hay que pedirle a cada issue lo que no le aplica: una issue de CI no necesita OpenAPI, y un cambio de documentación no necesita tests de integración.

La DoD tiene dos partes: lo que se exige **siempre** y lo que se exige **según lo que toque el cambio**. El PR indica qué partes aplican.

**Crear tags y releases no forma parte de la DoD de ninguna issue:** es un proceso aparte que cierra cada milestone ([proceso de release](../development/versioning.md#proceso-de-release)).

## Siempre (todo PR)

- [ ] Hace lo que pide la issue y cumple sus criterios de aceptación.
- [ ] CI en verde: compilación sin warnings, formato, tests, `verify()` de Modulith, gitleaks y Trivy.
- [ ] Sin código muerto, sin `TODO` sin issue asociada, sin logs de depuración olvidados.
- [ ] Nada sensible (secretos, tokens, valores de headers, contraseñas) en el código, los logs, las respuestas ni los mensajes de error.
- [ ] Los documentos de `docs/` afectados se actualizan **en el mismo PR**.
- [ ] PR con la plantilla completa (qué, por qué, cómo se probó, impacto en seguridad) y la [checklist de revisión](../development/code-standards.md#revisión-de-código) repasada.
- [ ] Merge con squash y título en formato Conventional Commits.

## Según lo que toque el cambio

| Si el cambio… | Además hace falta |
|---|---|
| **Añade o cambia lógica de dominio** | Tests unitarios de las reglas, sin Spring ni base de datos |
| **Toca persistencia** (consultas, bloqueos, restricciones) | Tests de integración con PostgreSQL real (Testcontainers) y `ddl-auto=validate` en verde |
| **Añade o cambia un endpoint** | Tests de API (caso feliz, validación, `401`, `403`, conflictos); **fila en la matriz de autorización y test de IDOR** con otra organización; errores con Problem Details y un `code` del catálogo; OpenAPI con descripción, ejemplos y errores; catálogo de endpoints actualizado |
| **Carga un recurso por id** | Pasa por `AccessControl`; la organización sale del recurso, nunca del cliente |
| **Acepta datos del usuario** | Validación de forma en el DTO y de reglas en el dominio; propiedades desconocidas rechazadas |
| **Cambia el esquema** | Migración nueva (nunca se edita una aplicada) que cumple la [checklist de migraciones](../database/migrations.md#checklist-de-revisión-de-una-migración); compatible con la versión anterior (expand / contract); `EXPLAIN` de las consultas afectadas |
| **Introduce concurrencia** (scheduler, colas, carreras entre usuarios) | Test de concurrencia contra PostgreSQL real con un criterio numérico (por ejemplo, N ejecuciones en paralelo y 0 duplicados); sin `Thread.sleep` |
| **Publica o escucha un evento** | Documentado en `events.md`; listener idempotente si es asíncrono; sin secretos en el payload |
| **Hace I/O externo** (HTTP, SMTP) | Pasa por `egress` si el destino lo pone un usuario; timeouts explícitos; ningún I/O dentro de transacciones; fallo simulado en los tests |
| **Añade un proceso en segundo plano, una cola o una dependencia externa** | Métricas sin etiquetas de alta cardinalidad y logs con contexto (`requestId`, `monitorId`) |
| **Añade una interacción externa, un endpoint público o un activo nuevo** | Threat model revisado |
| **Corrige un fallo de seguridad** | Test que reproduce la vulnerabilidad antes de la corrección; threat model actualizado |
| **Afecta al rendimiento** | Benchmark antes y después con el mismo procedimiento, y el resultado en `docs/performance/results/` |
| **Toma una decisión de arquitectura** | ADR nuevo o actualizado y las decisiones abiertas al día |
| **Cambia configuración** | Catálogo de propiedades actualizado; los secretos nuevos solo desde variables de entorno o Docker secrets |

## Tests proporcionales al riesgo

No se exigen tests sin valor (getters, mapeos triviales, configuración declarativa). Sí se exige cobertura fuerte en:

| Área | Tipo de test esperado |
|---|---|
| Autorización y aislamiento entre organizaciones | Seguridad (matriz de endpoints, IDOR) |
| SSRF | Unitarios (tabla de casos) y seguridad (resolver falso, redirects) |
| Scheduler y concurrencia | Integración con PostgreSQL real, con criterios numéricos |
| Ciclo de vida de incidentes | Unitarios (transiciones) y de módulo (`Scenario`) |
| Flyway y repositorios | Integración |
| Validación de la API | API |
| Límites de módulo | Arquitectura (`verify()` y ArchUnit) |
| Capacidad del motor | Rendimiento (fuera del pipeline de PR) |

Detalle en la [estrategia de testing](../testing/testing-strategy.md).

## Definition of Ready (antes de empezar una issue)

- [ ] Está en el milestone actual y marcada como **Ready** ([estados](backlog.md#estado-de-una-issue)).
- [ ] Criterios de aceptación verificables.
- [ ] Dependencias terminadas o sin bloqueo.
- [ ] Consideraciones de seguridad analizadas (no basta con "ninguna": si no hay implicaciones, se dice por qué).
- [ ] Si toca la arquitectura, existe un ADR o se sabe que hay que escribirlo.
- [ ] Cabe en una rama corta (días, no semanas). Si no, se parte.
