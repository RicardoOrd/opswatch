# Documentación de OpsWatch

Estado: diseño inicial · Última revisión: 2026-09-28

Esta carpeta explica qué se construye, por qué, cómo está diseñado, en qué punto va el trabajo y cómo debe evolucionar. Todavía no hay código: donde un documento describe comportamiento, describe el comportamiento **planificado**.

## ¿Qué se hace ahora?

**Sprint 0 — Fundaciones** (OW-001 a OW-010). El orden sugerido, lo que viene después y los milestones están en el [foco actual del roadmap](roadmap/roadmap.md#foco-actual). Las convenciones de trabajo (estados, prioridades, etiquetas, milestones) están en el [backlog](roadmap/backlog.md#convenciones), que es la fuente única de las issues de GitHub.

## Orden de lectura sugerido

Para entender el proyecto en una hora:

1. [Visión general](architecture/overview.md): contexto, contenedores y atributos de calidad.
2. [Módulos](architecture/modules.md): límites y reglas de dependencia.
3. [Modelo de dominio](architecture/domain-model.md): entidades, estados y glosario.
4. [Motor de monitoreo](architecture/monitoring-engine.md): la pieza central.
5. [Protección SSRF](security/ssrf-protection.md): el riesgo de seguridad más serio.
6. [Evolución](architecture/evolution.md): cuándo y por qué cambia la arquitectura.
7. [Roadmap](roadmap/roadmap.md) y [Sprint 0](roadmap/sprint-0.md): qué se hace ahora.

## Mapa

| Carpeta | Documento | Contenido |
|---|---|---|
| `architecture/` | [overview.md](architecture/overview.md) | Propósito, actores, atributos de calidad, diagramas C4 y despliegue |
| | [modules.md](architecture/modules.md) | Módulos, dependencias permitidas, estructura de paquetes y Spring Modulith |
| | [domain-model.md](architecture/domain-model.md) | Glosario, entidades, campos, reglas, ciclos de vida y enums |
| | [events.md](architecture/events.md) | Eventos internos, listeners, transacciones y consistencia |
| | [monitoring-engine.md](architecture/monitoring-engine.md) | Scheduling, concurrencia, cliente HTTP, timeouts, backpressure |
| | [incident-lifecycle.md](architecture/incident-lifecycle.md) | Cuándo se abre y se cierra un incidente, y los estados |
| | [evolution.md](architecture/evolution.md) | V1 a V5, criterios de paso y plan de extracción de Monitoring |
| | [open-decisions.md](architecture/open-decisions.md) | Decisiones que se mantienen abiertas y cuándo tomarlas |
| `security/` | [security-architecture.md](security/security-architecture.md) | Autenticación, tokens, secretos, cabeceras, validación, Docker y dependencias |
| | [authorization-model.md](security/authorization-model.md) | Multi-tenant, roles, matriz RBAC y prevención de IDOR |
| | [threat-model.md](security/threat-model.md) | Activos, fronteras de confianza y STRIDE |
| | [ssrf-protection.md](security/ssrf-protection.md) | Controles concretos contra SSRF y DNS rebinding |
| `api/` | [api-guidelines.md](api/api-guidelines.md) | Convenciones REST, errores (Problem Details), paginación |
| | [endpoints-v1.md](api/endpoints-v1.md) | Catálogo de endpoints `/api/v1` con DTOs y validaciones |
| `database/` | [database-design.md](database/database-design.md) | Esquema, claves, índices, bloqueos y DDL preliminar |
| | [migrations.md](database/migrations.md) | Reglas de Flyway y cambios compatibles hacia atrás |
| | [data-retention.md](database/data-retention.md) | Crecimiento de `monitor_checks`, retención, rollups y particionado |
| `testing/` | [testing-strategy.md](testing/testing-strategy.md) | Pirámide de pruebas, Testcontainers y qué no probar |
| `devops/` | [docker.md](devops/docker.md) | Dockerfile, Compose y endurecimiento de contenedores |
| | [ci-cd.md](devops/ci-cd.md) | Ramas, pipeline y despliegue |
| | [environments.md](devops/environments.md) | Perfiles, configuración, secretos y catálogo de propiedades |
| | [observability.md](devops/observability.md) | Logs, métricas y trazas |
| | [costs.md](devops/costs.md) | Costos por etapa |
| `development/` | [code-standards.md](development/code-standards.md) | Convenciones de código y herramientas de calidad |
| | [versioning.md](development/versioning.md) | SemVer y versionado de la API, las migraciones y los eventos |
| `performance/` | [benchmark-plan.md](performance/benchmark-plan.md) | Escenarios de 100 a 10 000 monitores, métricas y criterios de cuello de botella |
| `roadmap/` | [roadmap.md](roadmap/roadmap.md) | Fases 0 a 12 con Definition of Done y criterios de aceptación |
| | [sprint-0.md](roadmap/sprint-0.md) | Plan detallado del primer sprint |
| | [backlog.md](roadmap/backlog.md) | Issues listos para GitHub (fases 0 a 4) |
| | [definition-of-done.md](roadmap/definition-of-done.md) | Definition of Done global y por tipo de trabajo |
| | [risk-register.md](roadmap/risk-register.md) | Registro de riesgos |
| `adr/` | [README.md](adr/README.md) | Índice de ADR, plantilla y proceso |
| `portfolio/` | [project-story.md](portfolio/project-story.md) | Cómo contar el proyecto en una entrevista |

## Convenciones de esta documentación

- **Idioma.** Se escribe en español. Los nombres de clases, paquetes, eventos, endpoints, propiedades y conceptos técnicos con nombre establecido van en inglés (`MonitorWentDown`, `SKIP LOCKED`, `refresh token`).
- **Implementado o planificado.** Nada se describe como hecho si no existe en `main`. Cuando algo se implemente, el documento correspondiente se actualiza en el mismo pull request.
- **Una fuente por hecho.** Los valores configurables (intervalos, límites, TTL) están definidos en el [catálogo de propiedades](devops/environments.md#catálogo-de-propiedades). Los demás documentos los citan y, si hay una contradicción, manda el catálogo.
- **Las decisiones van en ADR.** Si un cambio contradice un ADR aceptado, se escribe un ADR nuevo que lo reemplace; el anterior no se edita para darle la vuelta.
- **Diagramas en Mermaid**, versionados junto al texto. Se usa C4 de forma conceptual (contexto, contenedores, componentes) con diagramas `flowchart`, porque el soporte de la sintaxis C4 de Mermaid sigue siendo experimental.
- **Código de ejemplo.** Los fragmentos de código de estos documentos son bocetos de diseño. La versión válida es la del repositorio cuando exista.
