# Backlog

Estado: sincronizado con GitHub Issues · Última revisión: 2026-10-02

Este fichero es la **fuente única** de las issues. Cada issue de GitHub se genera a partir de su entrada aquí: si una issue cambia, se cambia aquí y se vuelve a sincronizar con [`scripts/sync-issues.mjs`](../../scripts/sync-issues.mjs) (Node 22 y `gh` autenticado):

```bash
node scripts/sync-issues.mjs         # simulación: muestra qué cambiaría
node scripts/sync-issues.mjs --run   # aplica los cambios
```

El script crea las issues que faltan, actualiza título, cuerpo, etiquetas de tipo y prioridad y milestone, y cierra las entradas marcadas como **Cerrada**. Es idempotente: si GitHub ya coincide, no hace nada. No toca las etiquetas que no gestiona (por ejemplo `bug`). De OW-001 a OW-044, número de issue = número OW + 1 (OW-001 es la #2, porque la #1 es el PR de documentación). Las issues futuras (releases, Fase 6 en adelante) no siguen esa regla: se enlazan por su número real.

**Project de GitHub:** [OpsWatch](https://github.com/users/RicardoOrd/projects/3), público y enlazado al repositorio. Tiene un solo campo propio, `Status`: Backlog, Ready, In Progress, Review y Done. La prioridad y el tipo van en etiquetas y la fase en el milestone, que el Project muestra como campos nativos. `Status` no lo gestiona `sync-issues.mjs`: se mueve a mano al empezar una issue. Los workflows del Project (**Item closed** → Done, **Pull request merged** → Done, **Item added** → Backlog y **Auto-add** para las issues nuevas del repositorio) se activan desde la configuración del Project, porque la API de GitHub no permite activarlos.

**Foco actual: v0.4.0 — Incidentes y notificaciones**, refinada el 2026-10-05 contra lo que dejó construido la v0.3.0 (publicada el 2026-10-05, release #90). Orden: OW-032 → OW-033 → OW-035 → OW-036 → OW-043. OW-032 y OW-033 están **Hechas**; las demás, en **Ready**. La v0.3.0 (OW-024 a OW-030; OW-031 se fusionó en OW-026 y OW-027), la v0.2.0 (OW-019 a OW-022, OW-034 y OW-044), la v0.1.0 (OW-012 a OW-018 y OW-045) y el Sprint 0 están **Hechas**.

## Convenciones

### Estado de una issue

| Estado | Significado | Dónde se ve |
|---|---|---|
| **Ready** | Refinada, en el milestone actual y cumple la [Definition of Ready](definition-of-done.md#definition-of-ready-antes-de-empezar-una-issue). Se puede empezar | Línea de metadatos de la issue. Columna `Ready` del Project |
| **Planned** | Refinada, pero en un milestone futuro. Se revisa al empezar su milestone, porque el trabajo anterior puede cambiarla | Ídem. Columna `Backlog` |
| **Por detallar** | Existe el objetivo, no el detalle. No se empieza hasta refinarla | Ídem. Columna `Backlog` |
| **Hecha** | Implementada y mergeada. La entrada conserva su detalle como registro | Issue cerrada en GitHub. Columna `Done` |
| **Cerrada** | Cerrada sin implementarse como tal: fusionada en otra issue o resuelta fuera del código | Issue cerrada en GitHub |

El trabajo de las Etapas 2 a 5 (Redis, broker, microservicios, analytics) **no tiene issues**: vive en el [roadmap](roadmap.md), en las [decisiones abiertas](../architecture/open-decisions.md) y en los ADR propuestos 009 a 013. Se convierte en issues solo cuando su disparador se cumpla.

### Prioridad (etiquetas `P0` a `P3`)

| Prioridad | Cuándo | Ejemplo |
|---|---|---|
| **P0 · Crítica** | Bloquea todo el trabajo actual, o es un fallo de seguridad o de datos en `main`. Se atiende antes que cualquier otra cosa | OW-002 (sin esqueleto no se puede hacer nada) |
| **P1 · Alta** | Control de seguridad o de correctitud del que dependen otras piezas: autenticación, aislamiento entre organizaciones, SSRF, scheduler, invariante de incidentes, CI y tests | OW-020 (`TargetPolicy`) |
| **P2 · Normal** | Funcionalidad o mejora planificada sin ser crítica para la seguridad | OW-028 (estadísticas) |
| **P3 · Baja** | Mejora deseable que puede posponerse sin riesgo | OW-039 (lista de contraseñas comunes) |

La prioridad indica **importancia**. El **cuándo** lo indica el milestone. Una issue P1 de la v0.3.0 no se empieza antes que una P2 del Sprint 0.

El número `OW-NNN` es un identificador, no un orden: se asigna al crear la issue y no cambia, porque de él sale el número de la issue de GitHub. Una issue que se adelanta a un milestone anterior (OW-034) o que nace al partir otra (OW-044) conserva su número. Dentro de un milestone, el orden lo fijan las dependencias y se escribe al principio de su sección.

### Etiquetas

- **Tipo:** `feature`, `architecture`, `security`, `testing`, `devops`, `documentation`, `performance`, `refactor`, `bug`. Una issue puede tener más de un tipo.
- **Prioridad:** `P0`, `P1`, `P2`, `P3`. Exactamente una por issue.
- No hay etiquetas de fase: la fase la indica el milestone.

### Milestones

Cada fase del roadmap es un milestone. Cerrar un milestone de versión = publicar su release con el [proceso de release](../development/versioning.md#proceso-de-release). **Ninguna issue funcional exige crear un tag.**

| Milestone | Issues | Resultado demostrable |
|---|---|---|
| Sprint 0 — Fundaciones | OW-001 a OW-010 | Esqueleto que arranca con PostgreSQL, rechaza todo con `401` en formato Problem Details y tiene CI en verde |
| v0.1.0 — Identity y organizaciones | OW-012 a OW-018, OW-045 | Registro, login, organizaciones y roles, con el aislamiento entre organizaciones probado |
| v0.2.0 — Proyectos y monitores | OW-019 a OW-022, OW-034, OW-044 | Configurar monitores con la política SSRF aplicada al guardar |
| v0.3.0 — Motor de monitoreo | OW-024 a OW-030 | Checks reales, historial, uptime y métricas, sin duplicados con varias instancias |
| v0.4.0 — Incidentes y notificaciones | OW-032, OW-033, OW-035, OW-036, OW-043 | Una caída = un incidente, un aviso de apertura y uno de resolución |
| v0.5.0 — Endurecimiento de seguridad | OW-037 a OW-042 | Riesgos aceptados de forma temporal cerrados |
| v1.0.0 — V1 desplegada | Se crean al cerrar la v0.5.0 | V1 pública, recuperable y con CD |

La descripción de cada milestone en GitHub repite su objetivo y criterio de cierre del [roadmap](roadmap.md#milestones).

### Formato de cada entrada

Línea de metadatos: `tipos` · prioridad · milestone · estado. Después: Context, Objective, Tasks, Acceptance Criteria, Testing (clasificado por tipo), Security considerations, Dependencies y Definition of Done. La Definition of Done de cada issue solo añade lo específico a la [DoD global](definition-of-done.md).

---

## Sprint 0 — Fundaciones

### OW-001 · Completar la configuración del repositorio
`devops` · P1 · Milestone: Sprint 0 — Fundaciones · **Hecha**

- **Context:** el repositorio ya existe (2026-09-28) con `main` protegida, merge solo con squash y etiquetas. Faltan las convenciones que ayudan en cada PR.
- **Objective:** repositorio con plantillas, Dependabot y protección de secretos.
- **Tasks:**
  - [x] Repositorio público `RicardoOrd/opswatch`, `.gitignore` y `.gitattributes`.
  - [x] Protección de `main`: PR obligatorio (0 aprobaciones), aplicada también a admins, historial lineal, sin force-push ni borrado, conversaciones resueltas.
  - [x] Merge solo con squash y borrado automático de la rama.
  - [x] Etiquetas de tipo y prioridad.
  - [x] `.editorconfig`.
  - [x] Plantilla de PR con la checklist de la DoD. Plantillas de issue: feature, bug y release (con la checklist del [proceso de release](../development/versioning.md#proceso-de-release)).
  - [x] `dependabot.yml` para Maven y GitHub Actions. El ecosistema Docker se añade en OW-009, cuando exista el Dockerfile.
  - [x] Secret scanning y push protection activos (comprobado con la API). Alertas y actualizaciones de seguridad de Dependabot activadas.
- **Acceptance Criteria:** un PR nuevo muestra la plantilla; la pestaña de issues ofrece las tres plantillas; Dependabot aparece configurado; push protection está activo.
- **Testing:**
  - Manual: la configuración de seguridad del repositorio se comprueba con `gh api repos/RicardoOrd/opswatch --jq .security_and_analysis`. No se hace un push de prueba con un token falso: GitHub solo bloquea los tokens con formato reconocido, y ensuciaría el historial de alertas.
- **Security considerations:** push protection es la primera barrera contra la exposición de secretos (riesgo R-07). Dependabot empieza a vigilar la cadena de suministro desde el primer `pom.xml`.
- **Dependencies:** ninguna.
- **Definition of Done:** estado real de la configuración anotado en [CI/CD](../devops/ci-cd.md).

### OW-002 · Generar el proyecto Spring Boot con las dependencias base
`architecture` · P0 · Milestone: Sprint 0 — Fundaciones · **Hecha**

- **Context:** hace falta el esqueleto con versiones fijadas y solo las dependencias justificadas para el Sprint 0.
- **Objective:** proyecto Maven que compila, con el wrapper, sin warnings y con las verificaciones abiertas del Sprint 0 resueltas.
- **Tasks:**
  - [ ] Spring Initializr con las capacidades de la [tabla del Sprint 0](sprint-0.md#2-proyecto-spring-boot-ow-002).
  - [ ] Fijar versiones: Java 25, Spring Boot 4.x, Spring Modulith, springdoc, Testcontainers.
  - [ ] Plugins: Spotless (palantir), Surefire y Failsafe, JaCoCo (solo informe), Enforcer, compilador con `-Xlint:all -Werror`.
  - [ ] Resolver las [verificaciones abiertas](sprint-0.md#2-proyecto-spring-boot-ow-002) y anotarlas en los ADR correspondientes.
- **Acceptance Criteria:** `./mvnw verify` y `./mvnw spotless:check` en verde; `./mvnw dependency:tree` no muestra dependencias de fases futuras (OAuth2 Resource Server, HttpClient, Bucket4j, Mail).
- **Testing:**
  - Integración: `ApplicationStartupIT` (se completa en OW-007).
- **Security considerations:** cadena de suministro. Versiones fijadas, sin dependencias `SNAPSHOT` y sin dependencias que todavía no se usan (menos superficie de vulnerabilidades).
- **Dependencies:** ninguna.
- **Definition of Done:** versiones anotadas en el README y en los ADR afectados.

### OW-003 · Declarar los módulos y verificarlos con Spring Modulith
`architecture` `testing` · P1 · Milestone: Sprint 0 — Fundaciones · **Hecha**

- **Context:** los límites de módulo tienen que romper el build desde el principio ([ADR-003](../adr/ADR-003-spring-modulith.md)).
- **Objective:** los siete módulos declarados con sus dependencias permitidas y verificados en CI.
- **Tasks:**
  - [ ] `package-info.java` por módulo con `allowedDependencies` según [modules.md](../architecture/modules.md#4-reglas-de-dependencia) y `@NullMarked`.
  - [ ] `shared` como módulo `OPEN`.
  - [ ] `ModularityTests` (`verify()` y `Documenter`).
  - [ ] Reglas ArchUnit: sin inyección por campo, sin `Instant.now()`, sin `@Transactional` en `web`.
- **Acceptance Criteria:** una dependencia `monitoring → incident` introducida a propósito en una rama de prueba rompe el build.
- **Testing:**
  - Arquitectura: `ModularityTests` y reglas ArchUnit.
  - Manual: la prueba negativa de los criterios de aceptación.
- **Security considerations:** los límites de módulo son la base de un control de seguridad posterior: la regla "solo `egress` construye clientes HTTP" (OW-024) depende de que los límites se verifiquen en el build.
- **Dependencies:** OW-002.
- **Definition of Done:** la documentación generada por `Documenter` se publica como artefacto de CI (cuando exista OW-010).

### OW-004 · PostgreSQL, Flyway y Docker Compose para desarrollo local
`devops` · P1 · Milestone: Sprint 0 — Fundaciones · **Hecha**

- **Context:** la aplicación necesita PostgreSQL real desde el primer día. Hibernate nunca toca el esquema.
- **Objective:** `docker compose up -d postgres` y la aplicación conectada con Flyway activo.
- **Tasks:**
  - [x] `docker-compose.yml` con PostgreSQL 18 fijado por digest, healthcheck por TCP y puerto solo en `127.0.0.1`. Dependabot vigila el digest.
  - [x] `application.yml`: `ddl-auto=validate`, `open-in-view=false`, `clean-disabled=true`, UTC.
  - [x] Flyway con `db/migration` (sin migraciones todavía).
  - [x] `.env.example` y `scripts/dev-keys.sh`, que crea `.env` con una contraseña aleatoria y las claves de desarrollo en `secrets/`. El perfil `local` importa `.env`.
- **Acceptance Criteria:** la aplicación arranca con el perfil `local` contra el Compose; borrar el volumen y volver a arrancar funciona; `clean` de Flyway está desactivado incluso en `local`.
- **Testing:**
  - Integración: `ApplicationStartupIT` con Testcontainers: PostgreSQL 18, Flyway sin migraciones pendientes, `ddl-auto=validate` y `clean` rechazado. El contenedor compartido llega en OW-007.
- **Security considerations:** PostgreSQL no se publica fuera de `127.0.0.1`. Ninguna contraseña en ficheros versionados; `.env` ignorado por Git. `clean-disabled` evita borrar una base de datos por un error de configuración.
- **Dependencies:** OW-002.
- **Definition of Done:** instrucciones de arranque del README comprobadas desde un clon limpio.

### OW-005 · Manejo de errores base con Problem Details
`architecture` · P1 · Milestone: Sprint 0 — Fundaciones · **Hecha**

- **Context:** todos los errores deben tener el mismo formato ([guía de API](../api/api-guidelines.md#8-formato-de-error-problem-details-rfc-9457)).
- **Objective:** jerarquía `DomainException` y traducción uniforme a `application/problem+json`.
- **Tasks:**
  - [x] `ProblemCode` (catálogo con estado y título fijos) y la jerarquía `DomainException` en `shared.error`.
  - [x] `ProblemDetailsHandler` (`@RestControllerAdvice`) para las excepciones de dominio, la validación (`errors[]`), el JSON malformado, las propiedades desconocidas, los parámetros inválidos, el bloqueo optimista, `404`, `405`, `415` y `500`.
  - [x] `ProblemDetailsErrorController` para los errores fuera de Spring MVC (`/error`).
  - [x] `AuthenticationEntryPoint` y `AccessDeniedHandler` que delegan en el mismo handler. Se conectan al `SecurityFilterChain` en OW-008.
  - [x] `PageResponse<T>`.
  - [x] Mensajes de la API en inglés (locale fijo) y propiedades JSON desconocidas rechazadas.
- **Acceptance Criteria:** cada tipo de error devuelve el `code` del catálogo y el `requestId`; un `500` no revela ni la clase ni el mensaje de la excepción.
- **Testing:**
  - Slice (`@WebMvcTest`): `ProblemDetailsHandlerTest`, con un controlador de prueba (solo en `src/test`) que lanza cada excepción. Es un test de serialización, no de persistencia, así que no necesita PostgreSQL.
  - Seguridad: el `500` no contiene stack trace, SQL ni nombres de clase; el entry point de Spring Security produce el mismo formato.
- **Security considerations:** fuga de información por errores (T-17 del threat model). Los errores de autenticación no distinguen causas que ayuden a un atacante.
- **Dependencies:** OW-002.
- **Definition of Done:** el catálogo de códigos de la guía de API coincide con el código.

### OW-006 · Logging estructurado, request id, perfiles y salvaguardas de arranque
`devops` `security` · P2 · Milestone: Sprint 0 — Fundaciones · **Hecha**

- **Context:** hace falta trazabilidad por petición y configuración segura por entorno.
- **Objective:** logs correlacionables y perfiles que fallan si están mal configurados.
- **Tasks:**
  - [ ] `RequestIdFilter` (valida el header entrante, lo pone en el MDC y lo devuelve en la respuesta).
  - [ ] JSON ECS en `staging` y `production`; texto en `local`.
  - [ ] Ficheros por perfil ([entornos](../devops/environments.md)).
  - [ ] Validador de salvaguardas de arranque con las reglas aplicables ya (CORS, `ddl-auto`, `clean-disabled`, secretos obligatorios, Swagger).
  - [ ] Beans `Clock` y `RandomGenerator`.
- **Acceptance Criteria:** cada línea de log de una petición lleva `requestId`; un `X-Request-Id` con caracteres no permitidos o de más de 64 caracteres se sustituye; `production` con CORS `*` no arranca.
- **Testing:**
  - Unitarios: `RequestIdFilterTest`.
  - Seguridad: `ProductionGuardrailsTest`, un contexto por regla.
- **Security considerations:** el `requestId` entrante se valida para evitar la inyección en los logs (saltos de línea, secuencias de control). Las salvaguardas impiden desplegar producción con configuración de desarrollo.
- **Dependencies:** OW-002.
- **Definition of Done:** catálogo de propiedades actualizado con lo implementado.

### OW-007 · Infraestructura de tests con Testcontainers
`testing` · P1 · Milestone: Sprint 0 — Fundaciones · **Hecha**

- **Context:** sin H2. Los tests de integración usan PostgreSQL real y deben ser rápidos ([estrategia](../testing/testing-strategy.md#testcontainers)).
- **Objective:** un contenedor compartido, Surefire y Failsafe separados y convenciones claras.
- **Tasks:**
  - [x] `PostgresTestcontainer` con `@ServiceConnection`: un contenedor por JVM que sobrevive al cierre de cualquier contexto, con la imagen leída de `docker-compose.yml` (la única fuente: Dependabot actualiza el digest ahí).
  - [x] Surefire (`*Test`, `*Tests`) y Failsafe (`*IT`): los valores por defecto del `pom.xml` de Spring Boot, declarados en OW-002.
  - [x] `ApplicationStartupIT` (existe desde OW-004) usa `PostgresTestcontainer` y arranca la aplicación en puertos aleatorios reales.
  - [x] Nota en la [estrategia de testing](../testing/testing-strategy.md#rapidez) sobre la reutilización del contenedor en local.
- **Acceptance Criteria:** `./mvnw test` no arranca Docker; `./mvnw verify` sí; un solo contenedor de PostgreSQL por ejecución de la JVM. `ApplicationStartupIT` comprueba además, con la aplicación arrancada en puertos reales: `readiness` en `UP` en el puerto 8081, `/actuator/env` → `404` (heredado de OW-008) y una línea de log de una petición con su `requestId` (heredado de OW-006).
- **Testing:**
  - Integración: `ApplicationStartupIT` (PostgreSQL 18, Flyway, `ddl-auto=validate`, `clean` rechazado, readiness, `/actuator/env` → `404` y el `requestId` en el log de una petición) y `PostgresTestcontainerIT` (un solo contenedor para dos contextos distintos, vivo tras cerrarlos).
- **Security considerations:** la configuración solo para tests (`allowed-private-cidrs`, controladores de prueba, dobles de `EmailSender`) vive en `src/test` y en el perfil `test`, nunca en el código de producción; las salvaguardas de OW-006 impiden activarla en `production`. Probar contra la misma imagen de PostgreSQL que producción evita falsos verdes.
- **Dependencies:** OW-004.
- **Definition of Done:** tiempo de `./mvnw verify` anotado como baseline: 28 s en local ([estrategia de testing](../testing/testing-strategy.md#rapidez)).

### OW-008 · Seguridad base: denegar por defecto, cabeceras y CORS
`security` · P1 · Milestone: Sprint 0 — Fundaciones · **Hecha**

- **Context:** la seguridad es la base, no un añadido ([arquitectura de seguridad](../security/security-architecture.md)).
- **Objective:** todo cerrado salvo `health` y la documentación de la API en `local` y `staging`.
- **Tasks:**
  - [x] `SecurityFilterChain` sin estado: CSRF desactivado para la API con Bearer (justificado en un comentario), sin formulario de login ni HTTP Basic, y sin el usuario en memoria de Spring Boot.
  - [x] Cabeceras de seguridad de la [arquitectura de seguridad](../security/security-architecture.md#cabeceras-de-seguridad-respuestas-de-la-api). Swagger UI tiene su propia cadena, sin la CSP estricta.
  - [x] CORS desde `opswatch.security.cors.allowed-origins`.
  - [x] Actuator en el puerto 8081 con solo `health` e `info`.
- **Acceptance Criteria:** `GET /api/v1/cualquier-ruta` sin token → `401` con Problem Details; las cabeceras de seguridad están presentes; un origen no permitido no recibe cabeceras CORS. `GET :8081/actuator/env` → `404` necesita la aplicación arrancada y se comprueba en OW-007.
- **Testing:**
  - Seguridad (slice `@WebMvcTest`): `SecurityConfigurationTest`, con `401` en Problem Details, denegación por defecto, cabeceras, CORS con origen permitido y rechazado, sin form login ni HTTP Basic, y la documentación de la API fuera de la CSP estricta.
- **Security considerations:** denegar por defecto hace que un endpoint nuevo olvidado quede cerrado, no abierto. Exponer Actuator de más filtra configuración (T-53).
- **Dependencies:** OW-005.
- **Definition of Done:** la sección 6 de la arquitectura de seguridad refleja la configuración real.

### OW-009 · Dockerfile multi-stage y endurecimiento del contenedor
`devops` `security` · P2 · Milestone: Sprint 0 — Fundaciones · **Hecha**

- **Context:** imagen pequeña, no root y sin secretos ([Docker](../devops/docker.md)).
- **Objective:** imagen reproducible con healthcheck.
- **Tasks:**
  - [x] Dockerfile multi-stage con capas de Spring Boot, imágenes base fijadas por digest y healthcheck contra `liveness`.
  - [x] `.dockerignore` como lista de permitidos (`.mvn/`, `mvnw`, `pom.xml`, `src/main/`).
  - [x] Servicio `app` en Compose (`read_only`, `cap_drop`, `no-new-privileges`, límites de CPU y memoria y `stop_grace_period` para el apagado ordenado).
  - [x] Medir el tamaño de la imagen: 138 MB comprimida y 299 MB sin comprimir. `jlink` evaluado y aplazado ([Docker](../devops/docker.md#tamaño)).
  - [x] Añadir el ecosistema `docker` a `.github/dependabot.yml`, para que vigile las imágenes base fijadas por digest.
- **Acceptance Criteria:** `docker run --rm --entrypoint id opswatch:local` muestra el UID 10001; el contenedor llega a *healthy*; `docker history` no muestra secretos; la imagen pesa menos de 200 MB comprimida. El criterio inicial era menos de 250 MB sin comprimir, y se cambió en OW-009 con las mediciones de [Docker](../devops/docker.md#tamaño).
- **Testing:**
  - Manual y en CI: build de la imagen y los tres criterios de aceptación.
- **Security considerations:** proceso no root y código de la aplicación propiedad de root (un proceso comprometido no puede modificarlo); imagen base fijada por digest; sin secretos en `ARG`, `ENV` ni en las capas (T-51, T-52).
- **Dependencies:** OW-004.
- **Definition of Done:** tamaño anotado en `docker.md`.

### OW-010 · Pipeline de CI inicial
`devops` · P1 · Milestone: Sprint 0 — Fundaciones · **Hecha**

- **Context:** `main` debe estar siempre verde de forma verificable.
- **Objective:** CI con compilación, formato, tests, secretos, imagen y escaneo de vulnerabilidades.
- **Tasks:**
  - [x] `ci.yml` con los jobs `build`, `secrets-scan` e `image` ([CI/CD](../devops/ci-cd.md)).
  - [x] Acciones fijadas por SHA, `permissions` mínimos por job y `persist-credentials: false`. Validado con actionlint y zizmor.
  - [x] Trivy, que falla ante `CRITICAL` o `HIGH` con corrección disponible. En la primera pasada detectó 3 CVE críticas de Tomcat y 1 alta de jackson-databind, corregidas fijando parches por encima de Boot 4.1.1.
  - [x] Publicación en GHCR en los push a `main` (`:sha-<7>` y `:main`), además de la comprobación del UID y del tamaño de la imagen.
  - [x] `build`, `secrets-scan` e `image` como checks obligatorios en la protección de `main`, ligados a la app GitHub Actions y con la rama al día.
- **Acceptance Criteria:** un PR mal formateado falla; un PR con una clave privada falsa falla en gitleaks; un merge a `main` publica `ghcr.io/ricardoord/opswatch:sha-…`; un PR con los checks en rojo no se puede mergear.
- **Testing:**
  - Manual: PR de prueba #58, que falló por formato y por la clave falsa y quedó bloqueado. `verify()` de Modulith, comprobado en local con una dependencia prohibida.
- **Security considerations:** `packages: write` solo en el job `image`; acciones fijadas por SHA contra la manipulación de acciones de terceros (T-61); ningún secreto de la aplicación en el pipeline.
- **Dependencies:** OW-003, OW-007, OW-009.
- **Definition of Done:** tiempo total del pipeline anotado (unos 5 min en frío y 1 min 39 s con caches); la [configuración de GitHub](../devops/ci-cd.md) refleja los checks obligatorios.

### OW-011 · Publicar la documentación de diseño
`documentation` · P1 · Milestone: Sprint 0 — Fundaciones · **Cerrada**

Hecha el 2026-09-28: documentación publicada en el PR #1, issues creadas desde este backlog y enlaces internos verificados con un script.

---

## v0.1.0 — Identity y organizaciones

### OW-012 · Registro de usuarios
`feature` `security` · P2 · Milestone: v0.1.0 — Identity y organizaciones · **Hecha**

- **Context:** primer caso de uso real. Fija el patrón que seguirán los demás: DTO, servicio, dominio, migración y tests de API. Del Sprint 0 ya existen la cadena de seguridad que deja pasar `/api/v1/auth/**` sin autenticación, el rechazo de propiedades desconocidas en Jackson y los errores en Problem Details.
- **Objective:** `POST /api/v1/auth/register` según el [catálogo](../api/endpoints-v1.md#autenticación-identity).
- **Tasks:**
  - [x] Migración `V1__identity_create_users` con el [DDL](../database/database-design.md#9-ddl-preliminar).
  - [x] `IdGenerator` en `shared`: UUIDv7 (RFC 9562) con el `Clock` inyectado y `SecureRandom`. El id se asigna al construir la entidad, no al guardarla ([decisión](../database/database-design.md#3-uuid-o-bigint)).
  - [x] Entidad `User` con sus invariantes: email normalizado a minúsculas, solo ASCII imprimible y de hasta 254 caracteres; nombre de 1 a 100 caracteres sin caracteres de control ni de formato bidireccional. `version` es un `Long` nulo hasta el primer guardado, para que Spring Data inserte sin un `SELECT` previo.
  - [x] `RegistrationService` con `DelegatingPasswordEncoder`: bcrypt con el coste de `opswatch.security.password.bcrypt-strength` (12, y 4 en el perfil `test`). Hashea fuera de una transacción, para no retener una conexión durante los 250 ms de bcrypt.
  - [x] Validación de la contraseña: de 12 caracteres a 72 bytes UTF-8 (`PasswordRules` y la restricción `@PasswordPolicy`).
  - [x] Email duplicado → `409 conflict`, también cuando dos registros con el mismo email llegan a la vez: la violación de `ux_users_email` se traduce al mismo error.
  - [x] `RegisterUserRequest.toString()` oculta la contraseña ([logs y datos sensibles](../security/security-architecture.md#9-logs-y-datos-sensibles)). El nombre sigue la [convención de DTOs](../api/api-guidelines.md#6-dtos).
  - [x] Primer builder de test (`aUser()`), según la [convención](../testing/testing-strategy.md#convenciones); aplazado desde OW-007, que no tenía entidades.
  - [x] `DeploymentGuardrails`: `staging` y `production` no arrancan con un coste de bcrypt menor que 12, para que el 4 de los tests nunca llegue a un despliegue. Añadido durante la implementación.
- **Acceptance Criteria:** `201` con el usuario sin hash y `Location: /api/v1/me`; un email duplicado, sin distinguir mayúsculas, da `409`; una contraseña de 73 bytes da `400`; el hash guardado empieza por `{bcrypt}`; `RegisterUserRequest.toString()` no contiene la contraseña.
- **Testing:**
  - Unitarios: invariantes de `User`, validación de la contraseña (límites de 12 caracteres y 72 bytes con caracteres multibyte), `IdGenerator` (versión 7, variante RFC y orden por instante con un `Clock` fijo) y `toString()` de `RegisterUserRequest`.
  - Integración: `UserRepositoryIT` (índice único de email, inserción sin `merge`) y `RegistrationServiceIT` (dos registros simultáneos con el mismo email, 20 repeticiones: siempre una cuenta y un `409`).
  - API: `RegistrationApiIT` (`201`, `400` con `errors[]`, `409`, propiedad desconocida → `400`, log sin la contraseña ni el hash, documentación OpenAPI).
- **Security considerations:** el hash nunca sale en respuestas ni en logs; las propiedades desconocidas se rechazan (mass assignment). La contraseña se mide en bytes antes de hashear, porque bcrypt ignora lo que pasa de 72. El email solo admite ASCII imprimible: así pasarlo a minúsculas da lo mismo en Java y en PostgreSQL, y no caben caracteres parecidos a otros. El nombre rechaza los caracteres de formato bidireccional, que permitirían disfrazarlo ante otros miembros. Enumeración de emails aceptada hasta la Fase 5 (T-06), mitigada por el rate limit de OW-015.
- **Dependencies:** Sprint 0 (cerrado).
- **Definition of Done:** endpoint documentado en OpenAPI con sus errores; el [diseño de base de datos](../database/database-design.md#3-uuid-o-bigint), el [modelo de dominio](../architecture/domain-model.md) y los [estándares de código](../development/code-standards.md) describen igual la generación de ids.

### OW-013 · Login con access token JWT
`feature` `security` · P1 · Milestone: v0.1.0 — Identity y organizaciones · **Hecha**

- **Context:** [ADR-004](../adr/ADR-004-security-strategy.md): JWT RS256 de 15 minutos validado por el Resource Server de Spring Security. `GET /api/v1/me` devuelve solo los datos del usuario: `identity` no puede depender de `organization` ([módulos](../architecture/modules.md)), y las organizaciones del usuario con su rol ya salen en `GET /api/v1/organizations` (OW-016).
- **Objective:** `POST /api/v1/auth/login` que emite el access token, y validación del JWT en toda la API.
- **Tasks:**
  - [x] Dependencia del Resource Server (versión gestionada por Boot). En Boot 4 el starter es `spring-boot-starter-security-oauth2-resource-server`; `spring-boot-starter-oauth2-resource-server` está deprecado.
  - [x] `JwtEncoder` y `JwtDecoder` con las propiedades `opswatch.security.jwt.*` del [catálogo](../devops/environments.md#seguridad): `kid`, `iss`, `aud`, RS256 fijado y 30 s de tolerancia de reloj. El decoder solo acepta RS256 con las claves propias y valida `exp`, `iss`, `aud` y que `sub` sea un UUID con el `Clock` inyectado. Cambio respecto al plan: el `kid` es la huella RFC 7638 de la clave y la clave pública se deriva de la privada, así que no se configuran y no pueden contradecirse. Para rotar basta `previous-public-key`.
  - [x] Los rechazos del Resource Server (token ausente, inválido o caducado) salen como `401` en Problem Details, con el `AuthenticationEntryPoint` de OW-008. Un token rechazado lleva `WWW-Authenticate: Bearer error="invalid_token"` (RFC 6750), sin el motivo.
  - [x] `AuthenticationService` con hash señuelo para emails inexistentes, generado al arrancar con el mismo coste que los hashes reales. Un usuario `DISABLED` recibe el mismo `401 invalid-credentials`. Cada intento cuesta exactamente una comprobación de bcrypt; una contraseña de más de 72 bytes no la hace en ningún caso. Los fallos se registran como evento de seguridad `auth.login.failed`, con el email solo como hash.
  - [x] `CurrentUser` en `shared.security`, como parámetro de los controladores.
  - [x] `GET /api/v1/me` (id, email, nombre y fecha de alta).
  - [x] Claves: el perfil `local` lee las de `secrets/` con `configtree` (`scripts/dev-keys.sh` ya las genera; el servicio `app` de Compose monta la carpeta); los tests generan su propio par al arrancar (`TestJwtKeys`). Los despliegues leen los Docker secrets de `/run/secrets/`.
  - [x] Arranque sin claves: sin clave privada o `issuer`, o con una clave RSA de menos de 2048 bits, la aplicación no arranca **en ningún entorno** (validación de `JwtProperties` y `JwtKeys`), no solo en `staging` y `production`. `DeploymentGuardrails` añade que el `issuer` de un despliegue sea `https`.
- **Acceptance Criteria:** un login correcto da un token que sirve en `GET /api/v1/me`; unas credenciales erróneas o un usuario deshabilitado dan `401 invalid-credentials`, con el mismo cuerpo exista o no el email y una diferencia de tiempo mediana inferior a 50 ms entre los dos casos; un token inválido da `401` en Problem Details; un despliegue sin claves no arranca.
- **Testing:**
  - Seguridad: `JwtSecurityIT` con firma alterada, payload cambiado bajo una firma válida, `alg: none`, HS256 firmado con la clave pública, token caducado y sin `exp`, `iss` o `aud` incorrectos, `sub` que no es un UUID, `kid` desconocido y clave ajena con el `kid` propio (todos → `401`), más la tolerancia de 30 s.
  - API: `LoginApiIT` con login correcto, incorrecto, de un email inexistente y de un usuario deshabilitado (mismo cuerpo), la diferencia de tiempo mediana, `GET /api/v1/me`, el log sin email ni contraseña y la documentación OpenAPI.
  - Unitarios: `AuthenticationServiceTest` (una comprobación de bcrypt por intento, señuelo con el coste real), `JwtKeysTest`, `JwtConfigurationTest` (arranque sin claves o con una clave débil), `CurrentUserArgumentResolverTest` y la regla nueva de `DeploymentGuardrails`.
- **Security considerations:** algoritmo fijado contra la confusión de algoritmos (T-04); el token no lleva roles, así que la autorización nunca se basa en datos del token; la clave privada solo se lee de un secreto. El hash señuelo con el coste real evita que el tiempo de respuesta revele qué emails existen. Un access token emitido antes de deshabilitar al usuario vale hasta caducar (15 minutos como máximo): es el compromiso aceptado en ADR-004; el refresh sí se corta (OW-014).
- **Dependencies:** OW-012.
- **Definition of Done:** el flujo de autenticación de la arquitectura de seguridad coincide con lo implementado.

### OW-014 · Refresh token con rotación, detección de reutilización y logout
`feature` `security` · P1 · Milestone: v0.1.0 — Identity y organizaciones · **Hecha**

- **Context:** refresh token opaco en una cookie `HttpOnly`, que rota en cada uso y detecta la reutilización.
- **Objective:** `POST /api/v1/auth/refresh` y `POST /api/v1/auth/logout`, más la revocación de todas las sesiones de un usuario que usa el cambio de contraseña (OW-045).
- **Tasks:**
  - [x] Migración `identity_create_refresh_tokens`, igual al DDL del [diseño de base de datos](../database/database-design.md#9-ddl-preliminar).
  - [x] El login de OW-013 abre una familia y emite el primer refresh token.
  - [x] `RefreshTokenService`: emitir, rotar con `SELECT … FOR UPDATE`, detectar la reutilización, revocar la familia y revocar todas las familias de un usuario (`revokeAllOf`, para OW-045). Las reglas de rotación y de familia viven en la entidad `RefreshToken`. La revocación por reutilización se confirma aunque la petición termine en `401`, y se registra como evento de seguridad `auth.refresh.reuse_detected`.
  - [x] Cookie con `HttpOnly`, `Secure`, `SameSite=Strict` y `Path=/api/v1/auth`. Su `Max-Age` es lo que le queda al token; el logout la borra con `Max-Age=0`.
  - [x] Comprobación del header `Origin` en refresh y logout (`TrustedOrigins`): solo se aceptan el origen de `opswatch.security.jwt.issuer` y los de `opswatch.security.cors.allowed-origins`. Una petición sin `Origin`, o con `Origin: null`, se rechaza. Además, los dos endpoints solo aceptan `Content-Type: application/json` (la tercera capa de la [arquitectura de seguridad](../security/security-architecture.md#csrf)); el cuerpo se ignora.
  - [x] Un usuario `DISABLED` no refresca: `401` y su familia revocada (`USER_DISABLED`).
  - [x] Job de purga de tokens vencidos (`RefreshTokenPurgeJob`), diario con `opswatch.retention.cron`, en lotes de `batch-size` y con `refresh-tokens-grace` de gracia. Un token rotado se conserva hasta caducar, para seguir detectando su reutilización. Sin lock entre instancias: borrar es idempotente.
- **Acceptance Criteria:**
  - Refresh → token nuevo, y el anterior deja de servir.
  - Reutilizar el anterior → `401` y la familia entera revocada.
  - Logout → cookie borrada y familia revocada.
  - Refresh de un usuario deshabilitado → `401`.
  - `Origin` ajeno o ausente → `403`.
  - Dos refresh concurrentes con el mismo token (50 repeticiones): exactamente uno recibe `200`, el otro `401`, y la familia queda revocada en todos los casos. Es el comportamiento estricto elegido: dos pestañas que refrescan a la vez cierran la sesión (compromiso documentado en ADR-004).
- **Testing:**
  - Unitarios: lógica de rotación y de familias (`RefreshTokenTest`) y orígenes permitidos (`TrustedOriginsTest`).
  - Integración (concurrencia): `RefreshTokenIT` con PostgreSQL real: la carrera de dos refresh (50 repeticiones), la revocación de todas las sesiones de un usuario y la purga.
  - Seguridad y API: `RefreshApiIT` con los atributos de la cookie, rotación, reutilización, logout, usuario deshabilitado, `Origin` ajeno, ausente o `null` → `403` sin gastar el token, formulario → `415`, el token en claro nunca se guarda ni aparece en el log, y la documentación OpenAPI.
- **Security considerations:** solo se guarda el SHA-256 del token; la reutilización genera un evento de seguridad; `FOR UPDATE` evita que una carrera emita dos tokens válidos de la misma familia (T-03, T-09). La comprobación de `Origin` es defensa en profundidad sobre `SameSite=Strict`: los navegadores siempre envían `Origin` en un `POST`, así que rechazar su ausencia solo afecta a clientes que no son navegadores, que pueden añadirlo.
- **Dependencies:** OW-013.
- **Definition of Done:** cubiertos T-03 y T-09 del threat model.

### OW-015 · Rate limiting de los endpoints de autenticación
`security` · P1 · Milestone: v0.1.0 — Identity y organizaciones · **Hecha**

- **Context:** protección contra fuerza bruta y credential stuffing, sin bloquear cuentas. `application-deployed.yml` usaba `server.forward-headers-strategy=framework`, que acepta `X-Forwarded-For` de cualquier cliente que llegue a la aplicación: solo la red impedía falsear la IP.
- **Objective:** los límites de autenticación de la [tabla de rate limiting](../security/security-architecture.md#8-rate-limiting) con Bucket4j en memoria: login por IP y por email, registro por IP y refresh por IP.
- **Tasks:**
  - [x] Dependencia `com.bucket4j:bucket4j_jdk17-core` 8.20.0, confirmada con Ricardo. Caffeine, gestionado por Boot, para la cache.
  - [x] `AuthRateLimiter` con los límites `opswatch.security.rate-limit.*` del [catálogo](../devops/environments.md#seguridad) (`RateLimitProperties`, formato `10/1m`), llamado por `AuthController` antes de cualquier otro trabajo. La clave por email usa el email normalizado; la clave por IP agrupa las IPv6 por /64. Un intento que rechaza el límite por IP no cuenta contra el email.
  - [x] Buckets en una cache acotada de Caffeine: 10 000 claves por límite y expiración tras un periodo sin uso, cuando el bucket ya está lleno. Sin límite, millones de IP o de emails distintos agotarían la memoria.
  - [x] `429 rate-limited` con `Retry-After` en segundos redondeados hacia arriba y Problem Details (`RateLimitExceededException`). Un evento de seguridad `auth.rate_limited` por ráfaga, no por petición.
  - [x] IP real: `server.forward-headers-strategy=native` con `server.tomcat.remoteip.internal-proxies` limitado a Caddy, en notación CIDR y desde el entorno, porque la IP de Caddy cambia con la red de cada entorno. `DeploymentGuardrails` exige las dos. Corregidas la [arquitectura de seguridad](../security/security-architecture.md#5-transporte), el [catálogo](../devops/environments.md) y [Docker](../devops/docker.md#producción-fase-6) (Caddy con IP fija).
- **Acceptance Criteria:** el undécimo login por minuto desde una IP → `429`; el sexto intento por minuto contra un mismo email desde IPs distintas, aunque cambien las mayúsculas → `429`; el sexto registro en una hora desde una IP → `429`; el refresh número 31 en un minuto desde una IP → `429`; un `X-Forwarded-For` que no llega desde el proxy de confianza no cambia la IP usada.
- **Testing:**
  - Unitarios: `AuthRateLimiterTest` (límites, relleno, IPv6, expiración, un evento por ráfaga) y `RateLimitTest` (formato y valores por defecto del catálogo).
  - Integración: `AuthRateLimitIT` con `MutableClock`, contra el servidor real y no MockMvc, porque la IP la resuelve el `RemoteIpValve` de Tomcat. Tiene su propio contexto: el perfil `test` sube los límites porque todas las peticiones de MockMvc llegan desde 127.0.0.1.
  - Seguridad: suplantación de IP con `X-Forwarded-For`, con la configuración de servidor de `application-deployed.yml` y no la de los tests. 127.0.0.2 hace de Caddy y 127.0.0.1 de cualquier otro host.
- **Security considerations:** sin bloqueo de cuentas, para que un atacante no pueda dejar fuera a la víctima; `X-Forwarded-For` solo desde proxies de confianza; la cache acotada evita que el propio limitador sea una vía de agotamiento de memoria. Límite conocido: con varias instancias, los límites en memoria se multiplican (ADR-009).
- **Dependencies:** OW-013 y OW-014 (el límite de refresh necesita su endpoint).
- **Definition of Done:** la arquitectura de seguridad y el catálogo de propiedades describen la configuración de proxies que existe.

### OW-016 · Organizaciones y `AccessControl`
`feature` `security` · P1 · Milestone: v0.1.0 — Identity y organizaciones · **Hecha**

- **Context:** la organización es el tenant raíz y quien la crea queda como `OWNER`. Sus propios endpoints ya necesitan comprobar la membresía, así que `AccessControl` nace aquí. `requireForProject` espera a que existan los proyectos (OW-019). En la v0.1.0 nadie escucha `OrganizationDeleted`: sus listeners llegan en la v0.2.0, después del registro de publicaciones de eventos (OW-034).
- **Objective:** endpoints de organizaciones del catálogo y la API pública `AccessControl` del módulo `organization`.
- **Tasks:**
  - [x] Migración `V3__organization_create_organizations_and_memberships`, igual al DDL del [diseño de base de datos](../database/database-design.md#9-ddl-preliminar).
  - [x] Entidades `Organization` y `Membership` (clave compuesta con `@IdClass`; la organización y el usuario se referencian por id).
  - [x] `Role` → `Permission` como mapa inmutable, según la [matriz](../security/authorization-model.md#matriz-rbac).
  - [x] `AccessControl.require`: `404` a quien no es miembro, `403` al miembro sin permiso (con el evento `authz.denied`). Una organización borrada da `404` a todos, también a sus miembros. Devuelve el rol, para el `myRole` de las respuestas.
  - [x] `OrganizationService`: creación con membresía `OWNER` en la misma transacción, borrado lógico y evento `OrganizationDeleted`.
  - [x] Cuota `opswatch.limits.organizations-per-user`: cuenta las organizaciones no borradas de las que el usuario es `OWNER`. Se serializa por usuario (`pg_advisory_xact_lock` de dos claves, con el espacio `1` para las cuotas), para que dos creaciones simultáneas no la superen.
  - [x] `ETag` e `If-Match` en `PATCH`, también en `GET` y `PATCH /api/v1/me` (`ETags`, en `shared.web`).
  - [x] Paginación por offset con lista blanca de `sort` (`PageQuery` y su resolver, en `shared.web`): es el primer listado. `size` > 100, `page` negativa o un `sort` no permitido dan `400 invalid-parameter`, sin corregirlos en silencio.
  - [x] La regex de nombres visibles pasa de `User` a `shared.text.VisibleText`, para validar también el nombre de la organización.
- **Acceptance Criteria:** una organización nueva aparece en `GET /api/v1/organizations` con `myRole: OWNER`; un no miembro recibe `404` en `GET /api/v1/organizations/{orgId}`; un `VIEWER` recibe `403` en `PATCH`; la sexta organización de un usuario → `422 quota-exceeded`, también con creaciones simultáneas; un `If-Match` obsoleto → `412`; después de borrarla, sus miembros reciben `404` y deja de aparecer en su listado.
- **Testing:**
  - Unitarios: mapa `Role` → `Permission` contra una tabla de datos (`RoleTest`); `OrganizationTest`, `ETagsTest` y `PageQueryArgumentResolverTest`.
  - Integración (concurrencia): creaciones simultáneas en el límite de la cuota (`OrganizationQuotaIT`, 6 hilos y 5 repeticiones). Sin el lock entran varias: comprobado quitándolo.
  - API: endpoints por rol, `412` y organización borrada (`OrganizationApiIT`; los roles distintos de `OWNER` se insertan en la tabla hasta que OW-017 los gestione). `ETag` e `If-Match` de `/me` en `MeApiIT`.
  - Seguridad: IDOR (usuario de la organización B contra la A → `404` sin efectos, igual que un id inexistente).
- **Security considerations:** es el control del que dependen T-10 y T-11. La organización se obtiene del recurso, nunca de datos del cliente; los listados solo devuelven las organizaciones de las que el usuario es miembro. Una cuota que se comprueba sin serializar se supera con peticiones en paralelo.
- **Dependencies:** OW-013.
- **Definition of Done:** eventos documentados en `events.md`; la matriz del modelo de autorización coincide con el código.

### OW-017 · Miembros y roles con la invariante del último `OWNER`
`feature` `security` · P1 · Milestone: v0.1.0 — Identity y organizaciones · **Hecha**

- **Context:** las reglas de gestión de roles del [modelo de autorización](../security/authorization-model.md#reglas-que-la-matriz-no-expresa).
- **Objective:** endpoints de miembros con todas las reglas.
- **Tasks:**
  - [x] `MembershipService` con `SELECT … FOR UPDATE` sobre la organización. Se autoriza con el bloqueo tomado, y la regla del último `OWNER` va antes que el permiso: el perdedor de la carrera recibe `409`.
  - [x] Reglas `ADMIN` frente a `OWNER` (`MembershipPolicy.toManage`), sin autopromoción, abandonar la organización. Bajarse el propio rol no necesita permiso, igual que abandonar: se añadió al modelo de autorización.
  - [x] `UserDirectory` (API pública de `identity`) para buscar usuarios por id y por email.
  - [x] Cuota de miembros (`opswatch.limits.members-per-organization`, 50), comprobada con el bloqueo tomado.
  - [x] `ETag` e `If-Match` en el `PATCH` de miembros, y `sort` por `joinedAt` en el listado (`PageQuery` admite ahora nombres de la API distintos de los de la entidad y otro desempate que `id`).
- **Acceptance Criteria:**
  - Un `ADMIN` no puede asignar `ADMIN` → `403`.
  - Un `ADMIN` no puede subirse a sí mismo a `OWNER` → `403`.
  - El último `OWNER` no puede abandonar → `409`.
  - Dos `OWNER` que se degradan el uno al otro a la vez (50 repeticiones): siempre queda al menos un `OWNER` y una de las dos peticiones recibe `409`.
- **Testing:**
  - Unitarios: reglas de cambio de rol (`MembershipPolicyTest`).
  - Integración (concurrencia): la carrera de los dos `OWNER` contra PostgreSQL real (`MembershipRaceIT`, 50 repeticiones). Sin el `FOR UPDATE`, los dos cambios entran y la organización se queda sin `OWNER`: comprobado quitándolo.
  - API y seguridad: endpoints por rol e IDOR (`MemberApiIT`).
- **Security considerations:** escalada de privilegios dentro de la organización (T-11) y carrera que deja una organización sin dueño (T-16). La enumeración de emails al añadir miembros es un riesgo aceptado hasta las invitaciones de OW-038 (T-06).
- **Dependencies:** OW-016.
- **Definition of Done:** las reglas de la sección 3 del modelo de autorización coinciden con el código.

### OW-018 · Matriz de autorización probada
`security` `testing` · P1 · Milestone: v0.1.0 — Identity y organizaciones · **Hecha**

- **Context:** la autorización por recurso es la defensa principal contra el IDOR. Tiene que ser imposible añadir un endpoint sin probar su autorización.
- **Objective:** la tabla endpoint × rol como test, con un test de completitud que la mantiene al día.
- **Tasks:**
  - [x] Tabla de datos endpoint × {`OWNER`, `ADMIN`, `MEMBER`, `VIEWER`, no miembro, anónimo} → código esperado: los 16 endpoints de la v0.1.0, como texto legible en `EndpointAuthorizationMatrixIT`.
  - [x] Test parametrizado que recorre la tabla contra la aplicación real: 96 casos, cada uno con su organización nueva (SQL y tokens emitidos directamente) y una petición válida por endpoint.
  - [x] Test de completitud: todo endpoint registrado en Spring MVC bajo `/api/` tiene fila y petición, toda fila corresponde a un endpoint y todo endpoint declara su método HTTP.
- **Acceptance Criteria:** los endpoints de la v0.1.0 están en la tabla; añadir un endpoint sin fila hace fallar el build; cambiar un permiso sin actualizar la tabla hace fallar el build.
- **Testing:**
  - Seguridad: `EndpointAuthorizationMatrixIT` y el test de completitud. Comprobado que fallan al quitar `ORGANIZATION_UPDATE` a `ADMIN` y al borrar una fila.
- **Security considerations:** convierte T-10 y T-11 en una comprobación automática en cada PR, en lugar de depender de la revisión.
- **Dependencies:** OW-016, OW-017.
- **Definition of Done:** la [estrategia de testing](../testing/testing-strategy.md#pruebas-de-seguridad) describe cómo añadir filas.

### OW-045 · Perfil del usuario: editar el nombre y cambiar la contraseña
`feature` `security` · P2 · Milestone: v0.1.0 — Identity y organizaciones · **Hecha**

- **Context:** el [catálogo](../api/endpoints-v1.md#autenticación-identity) y la [Fase 1 del roadmap](roadmap.md#fase-1-identity-y-organizaciones--010) incluyen `PATCH /api/v1/me` y `POST /api/v1/me/password`, pero ninguna issue los implementaba. Detectado al refinar la v0.1.0 (2026-09-29).
- **Objective:** `PATCH /api/v1/me` y `POST /api/v1/me/password`.
- **Tasks:**
  - [x] `PATCH /api/v1/me` con `displayName` como único campo editable. El email no cambia en V1: cambiarlo exige verificarlo (OW-037). Un `displayName: null` explícito da `400` en lugar de tomarse por ausente (`NotNullIfPresent`, en `shared.web`). `ETag` e `If-Match` pasan a OW-016, que crea el mecanismo.
  - [x] `POST /api/v1/me/password` con `currentPassword` y `newPassword`: comprueba la actual, valida la nueva con las reglas de OW-012 y revoca todas las familias de refresh tokens del usuario (`PASSWORD_CHANGED`) en la misma transacción. Los dos bcrypt van fuera de la transacción; dentro se comprueba que el hash sigue siendo el verificado (si otro cambio se adelantó, `409`). La contraseña actual incorrecta es un `InvalidFieldException`: `400 validation-error` con `incorrect-password` en `currentPassword`. Eventos `auth.password.changed` y `auth.password.change_failed`.
  - [x] Límite `opswatch.security.rate-limit.password-change-per-user` (`5/15m`) con el limitador de OW-015, por usuario y desde cualquier IP.
  - [x] `toString()` de los DTOs con contraseñas oculta sus valores.
- **Acceptance Criteria:** `PATCH` con `displayName` → `200`; con `email` → `400` (propiedad desconocida); cambio con la contraseña actual correcta → `204`, y el refresh token de antes da `401`; con la actual incorrecta → `400` con el error en `currentPassword`; el sexto intento en 15 minutos → `429`.
- **Testing:**
  - API: `MeApiIT` y `PasswordChangeApiIT`. El límite de cambio de contraseña se prueba con su valor real en el contexto compartido: va por usuario y cada test crea el suyo.
  - Integración: el cambio revoca todas las familias del usuario, y solo las suyas.
  - Seguridad: `toString()` de los DTOs y log del cambio sin contraseñas.
  - Unitarios: `ProfileServiceTest` (contraseña incorrecta, cambio adelantado por otro), `UserTest`, `NotNullIfPresentTest` y `AuthRateLimiterTest`.
- **Security considerations:** pedir la contraseña actual impide que un access token robado sirva para quedarse la cuenta, y el límite por usuario impide usarlo para adivinarla. Revocar todas las sesiones echa a quien tuviera un refresh token robado; los access tokens ya emitidos valen hasta caducar (ADR-004). La contraseña actual incorrecta da `400` y no `401`: el access token es válido, y un `401` haría que el cliente intentara refrescarlo.
- **Dependencies:** OW-014 y OW-015.
- **Definition of Done:** endpoints documentados en OpenAPI con sus errores; el límite nuevo en el catálogo de propiedades y en la tabla de rate limiting.

---

## v0.2.0 — Proyectos y monitores

Orden: OW-034 → OW-019 → OW-020 → OW-021 → OW-022 → OW-044. OW-034 va primero porque OW-044 lo necesita y no depende de nada; OW-022 va después de OW-021 porque cifra una columna de la entidad `Monitor`, que OW-021 crea.

### OW-034 · Event Publication Registry de Spring Modulith
`architecture` · P2 · Milestone: v0.2.0 — Proyectos y monitores · **Hecha**

- **Context:** el primer listener asíncrono entre módulos (`ProjectDeleted` → `monitoring`, en OW-044) necesita un registro persistente para no perder eventos ([eventos](../architecture/events.md)). Antes estaba en la Fase 4, pero OW-044 lo necesita en la v0.2.0. Hasta esta issue solo estaba `spring-modulith-starter-core`: no había registro, y `OrganizationDeleted` se publicaba sin que nadie lo escuchara.
- **Objective:** registro JDBC activo, con reenvío al reiniciar, publicaciones completadas archivadas y una purga del archivo que nunca toca una publicación pendiente.
- **Tasks:**
  - [x] Dependencia `spring-modulith-starter-jdbc` (la versión la fija el BOM 2.1.1). JDBC y no JPA: el registro no necesita entidades, y así no depende del contexto de persistencia de cada caso de uso.
  - [x] Migración `V4__modulith_create_event_publication` con las tablas `event_publication` y `event_publication_archive`: las columnas del esquema **v2** de Spring Modulith 2.1.1 (`status`, `completion_attempts`, `last_resubmission_date`), con los índices nombrados según el diseño de base de datos. Flyway es el dueño del esquema: `spring.modulith.events.jdbc.schema-initialization.enabled=false`.
  - [x] `spring.modulith.events.republish-outstanding-events-on-restart=true` y `spring.modulith.events.completion-mode=archive`: una publicación completada pasa al archivo, no se borra. Nombres comprobados en el código de la versión fijada.
  - [x] `EventPublicationPurgeJob` (`shared.events`, con el cron de retención): borra del **archivo** las publicaciones completadas hace más de `opswatch.retention.event-publications` (7 días), con `CompletedEventPublications.deletePublicationsOlderThan`, sin SQL a mano. Comprobado en el código de Spring Modulith 2.1.1: en modo `archive` esa llamada solo ejecuta `DELETE` sobre `event_publication_archive` y solo de filas completadas. Nunca borra de `event_publication`.
  - [x] Gauge `opswatch_event_publications_incomplete`, recalculado cada 30 s por `IncompleteEventPublicationsMonitor` (nunca en el scrape), y un `WARN` mientras alguna publicación lleve pendiente más de 15 minutos (`opswatch.events.*`).
  - [x] Propiedades en el [catálogo de entornos](../devops/environments.md) y retención en [data-retention](../database/data-retention.md).
  - [x] Awaitility declarada como dependencia de test (antes llegaba solo de forma transitiva), para esperar a los listeners asíncronos sin `sleep` fijos. Añadido durante la implementación.
- **Acceptance Criteria:**
  - Si el contexto se para después del commit y antes de que el listener termine, al reiniciar el listener se ejecuta y la publicación acaba en el archivo.
  - Una publicación completada no queda en `event_publication`, sino en `event_publication_archive`.
  - La purga borra las archivadas de más de 7 días y deja las más recientes. Una publicación **pendiente** de más de 7 días sigue intacta después de la purga.
- **Testing:**
  - Integración: `EventPublicationRegistryIT` con dos contextos sobre el mismo PostgreSQL, uno detrás de otro: en el primero el listener no termina y la publicación queda pendiente (y un publicador que hace rollback no deja ninguna); el primero se cierra y el segundo la reenvía al arrancar, la ejecuta una vez y la archiva. Con `republish-outstanding-events-on-restart=false` o `completion-mode=update`, el test falla (comprobado).
  - Integración: `EventPublicationPurgeJobIT` (archivadas justo antes y justo después del límite de 7 días; una pendiente y una completada sin archivar de hace un año, en `event_publication`, que sobreviven) e `IncompleteEventPublicationsMonitorIT` (gauge, recuento de atrasadas y `WARN`). Los tiempos se fijan respecto al `Clock` del contexto, sin `MutableClock`: el registro usa el mismo bean.
- **Security considerations:** el payload de los eventos se guarda en claro en las dos tablas, durante 7 días en el archivo, así que ningún evento puede llevar secretos ni datos personales más allá de ids (se revisa en cada evento nuevo). Entrega at-least-once: los listeners tienen que ser idempotentes para que un duplicado no cause efectos dobles (T-33, R-13). El reenvío al reiniciar también reenvía lo que otra instancia tenga en vuelo, por la misma razón. La purga es la única operación que borra, y solo borra lo archivado: una publicación pendiente es trabajo sin hacer y no caduca.
- **Dependencies:** Sprint 0.
- **Definition of Done:** sección 4 de [eventos](../architecture/events.md) actualizada con las propiedades reales.

### OW-019 · Proyectos
`feature` · P2 · Milestone: v0.2.0 — Proyectos y monitores · **Hecha**

- **Context:** agrupación de monitores dentro de una organización. De la v0.1.0 ya existen `AccessControl.require`, `PageQuery`, `ETags`, `NotNullIfPresent`, `VisibleText`, la cuota serializada con advisory lock y la matriz de autorización. `OrganizationDeleted` se publica desde OW-016, pero sus proyectos todavía no se borran porque no existen.
- **Objective:** endpoints de proyectos del [catálogo](../api/endpoints-v1.md#proyectos), la API pública que `monitoring` necesita (`AccessControl.requireForProject` y `ProjectDirectory`) y el borrado de los proyectos con su organización.
- **Tasks:**
  - [x] Migración `V5__organization_create_projects` con el [DDL](../database/database-design.md#9-ddl-preliminar): índice único parcial `(organization_id, lower(name))` y `UNIQUE (id, organization_id)` para la FK compuesta de los monitores.
  - [x] Entidad `Project` (nombre con `VisibleText`, de 1 a 100 caracteres; descripción opcional de hasta 500, también sin caracteres de control ni de formato bidireccional). El accesor de la versión es `savedVersion()`, como en `Organization`.
  - [x] `ProjectService`: crear, listar, consultar, editar y borrar. Nombre duplicado en la organización → `409 conflict`, también con dos creaciones simultáneas: la violación del índice único se traduce al mismo error. Borrado lógico y evento `ProjectDeleted(projectId, organizationId, occurredAt, deletedBy)`.
  - [x] Cuota `opswatch.limits.projects-per-organization` (20) serializada por organización. **Cambiado durante la implementación:** en lugar de un advisory lock, crear un proyecto bloquea la fila de la organización (`findActiveByIdForUpdate`, el bloqueo que ya usan los cambios de miembros), y borrar la organización toma el mismo bloqueo. Serializa la cuota y la comprobación del nombre, y además cierra una carrera que el advisory lock dejaba abierta: un proyecto creado mientras se borra su organización quedaba vivo en una organización borrada. Mover los espacios de los advisory locks a `shared` pasa a OW-021, el primero que necesita uno nuevo.
  - [x] Borrar una organización borra sus proyectos en la misma transacción: `OrganizationService.delete` llama a `ProjectService`, que publica un `ProjectDeleted` por proyecto. Es una llamada dentro del módulo y no un listener de `OrganizationDeleted`: dentro de un módulo, un evento solo añadiría indirección. Se actualiza el Javadoc de `OrganizationDeleted`.
  - [x] `AccessControl.requireForProject(userId, projectId, permission)`: carga el proyecto no borrado y comprueba el permiso en su organización. Devuelve `ProjectRef(projectId, organizationId)`. Un proyecto inexistente, borrado o de otra organización da el mismo `404`, con un detalle sobre el proyecto (`project <id> was not found`): el de `require` nombraría la organización y revelaría a quién pertenece el proyecto.
  - [x] `ProjectDirectory.lockActive(projectId)`: `SELECT … FOR SHARE` sobre el proyecto no borrado, dentro de la transacción de quien llama (`Propagation.MANDATORY`: sin transacción, falla en vez de devolver un bloqueo que ya terminó). Lo usa OW-021 para que un monitor no se cree en un proyecto que se está borrando.
  - [x] `PATCH` con `description: null` borra la descripción y sin `description` la conserva. `NotNullIfPresent` no distingue esos dos casos: nace `shared.web.PatchField<T>` (ausente, `null` o valor) con su deserializador (`getAbsentValue` y `getNullValue`, igual que `NotNullIfPresent`) y un `ValueExtractor` de Bean Validation registrado en `META-INF/services`, para validar el valor de dentro con restricciones en el argumento de tipo (`PatchField<@Size(max = 500) String>`). Lo reutilizará OW-021 para `degradedThresholdMs`. `name: null` → `400`.
  - [x] `ETag` en `POST`, `GET` y `PATCH`, e `If-Match` opcional en `PATCH`, igual que en organizaciones.
  - [x] Listado paginado con `sort` por `name` (por defecto) o `createdAt`.
  - [x] Filas nuevas en la matriz de autorización (OW-018), con un proyecto en su `Fixture`.
- **Acceptance Criteria:**
  - Un `ADMIN` crea un proyecto; un `MEMBER` recibe `403`; un no miembro, `404` en todos los endpoints con id.
  - Un nombre duplicado en la misma organización, sin distinguir mayúsculas → `409`, también con dos creaciones simultáneas. Se puede repetir en otra organización o después de borrar el proyecto.
  - El proyecto 21 de una organización → `422 quota-exceeded`, también con creaciones simultáneas.
  - `PATCH {"description": null}` borra la descripción, `PATCH {}` no cambia nada y `PATCH {"name": null}` → `400`.
  - Un `If-Match` obsoleto → `412`.
  - Al borrar la organización, sus proyectos quedan borrados y se publica un `ProjectDeleted` por cada uno.
- **Testing:**
  - Unitarios: `ProjectTest` (invariantes, longitudes en caracteres y no en unidades UTF-16, formato bidireccional) y `PatchFieldTest` (deserializador y validación del valor de dentro).
  - Integración (concurrencia): `ProjectConcurrencyIT`. Creaciones simultáneas en el límite de la cuota y con el mismo nombre en mayúsculas distintas (6 hilos, 5 repeticiones). Dos esperas deterministas: un borrado espera a la transacción que hizo `lockActive`, y una creación espera a la que bloqueó la organización. Quitando cualquiera de los dos bloqueos, los tests de cuota y de espera fallan (comprobado). Y alguien ajeno recibe su `404` sin esperar al bloqueo de la organización (sin la autorización previa, el test falla: comprobado).
  - API y seguridad: `ProjectApiIT` (endpoints por rol, IDOR con el detalle del `404` que no nombra la organización, `organizationId` en el cuerpo → `400`, nombre único sin distinguir mayúsculas, cuota, `PATCH` parcial, `412`, borrado y borrado de la organización con un `ProjectDeleted` por proyecto, capturado con `@RecordApplicationEvents`, y OpenAPI) y la matriz de autorización. Los eventos se comprueban en la aplicación completa, como en `OrganizationApiIT`, y no con `@ApplicationModuleTest`.
- **Security considerations:** la creación se autoriza sobre la organización de la ruta con `AccessControl`, nunca con un `organizationId` del cuerpo (mass assignment, T-12). `requireForProject` es la puerta de todos los recursos que cuelgan de un proyecto: un fallo aquí rompe el aislamiento de monitores, incidentes y canales (T-10, T-11). La descripción la ven todos los miembros: se le aplican las mismas reglas de caracteres que al nombre, para que no pueda disfrazar contenido. Crear un proyecto y borrar una organización autorizan antes de bloquear la fila de la organización, y otra vez con el bloqueo tomado: alguien ajeno no puede retener ese bloqueo para frenar a los miembros.
- **Dependencies:** OW-018.
- **Definition of Done:** `ProjectDeleted` documentado en [eventos](../architecture/events.md) con su listener de OW-044; la firma de `requireForProject` del [modelo de autorización](../security/authorization-model.md#4-cómo-se-decide-en-cada-petición) coincide con el código.

### OW-020 · `TargetPolicy`: validación de las URL de destino
`security` · P1 · Milestone: v0.2.0 — Proyectos y monitores · **Hecha**

- **Context:** capa 1 de la [protección SSRF](../security/ssrf-protection.md#capa-1-validación-al-guardar). El módulo `egress` existe desde OW-003, vacío; aquí recibe su primer código, por el que pasará todo el HTTP saliente. `TargetNotAllowedException` (`422 target-not-allowed`) ya existe en `shared.error`.
- **Objective:** `TargetPolicy` e `IpRangeClassifier` en `egress`, deterministas en los tests.
- **Tasks:**
  - [x] `IpRangeClassifier` con todos los rangos IPv4 e IPv6 de la [tabla](../security/ssrf-protection.md#rangos-bloqueados), incluidas las IPv4 incrustadas (mapeada, NAT64, 6to4), en un único sitio. Lo reutiliza `GuardedDnsResolver` en OW-024. **Más estricto que la tabla original:** IPv6 se decide por exclusión (fuera de `2000::/3`, bloqueado), y se bloquean enteros `2001::/23`, `3fff::/20` y el NAT64 de uso local `64:ff9b:1::/48`.
  - [x] Parser estricto (`TargetUrlParser`): solo `http` y `https`, sin `userinfo`, puertos permitidos, formas de IP no canónicas rechazadas, hostnames prohibidos (`localhost`, `*.internal`, una sola etiqueta), IDN a punycode y fragmento descartado. `InetAddress.ofLiteral` no basta para las IPv4: acepta `127.1` y `01.2.3.4`, así que las literales pasan antes por un patrón estricto (`IpLiterals`). El punto final del host se quita antes de las reglas (`localhost.`).
  - [x] `TargetPolicy.validate(url, kind)` devuelve la URL **normalizada** (esquema y host en minúsculas, host en punycode, sin fragmento). Es la que se guarda y la que devuelve la API.
  - [x] Resolución DNS al guardar, con todas las IP resueltas clasificadas, a través de una interfaz propia de `egress` (no `InetAddress` directamente), para que los tests usen un resolver falso y ningún test dependa del DNS real.
  - [x] Plazo máximo de la resolución (`opswatch.egress.save-resolution-timeout`, 2 s). Un nombre que no resuelve, o que no responde a tiempo, se admite sin aviso en la respuesta: la capa 2 decide en cada check, y el primer check mostrará `DNS_FAILURE`. Las resoluciones corren en virtual threads, **32 a la vez como mucho**: una que no responde conserva su permiso hasta que el resolver del sistema se rinde, así que los nombres lentos no se acumulan sin límite (añadido durante la implementación).
  - [x] Propiedad `opswatch.egress.allowed-private-cidrs` con sus salvaguardas: vacía por defecto, solo literales en notación CIDR (un bloque inválido impide arrancar), nunca abre la metadata cloud y `DeploymentGuardrails` no arranca con ella en `production` (ya estaba desde el Sprint 0).
- **Acceptance Criteria:** los casos 1 a 15, 24, 25 y 26 de la [tabla de SSRF](../security/ssrf-protection.md#5-casos-de-prueba-obligatorios) dan el resultado esperado; `HTTP://Ejemplo.COM/x#frag` se normaliza a `http://ejemplo.com/x`; un resolver que tarda más que el plazo no retiene la petición más de 2 s. Los casos 20 y 21 son de headers y pasaron a OW-022 con su validación.
- **Testing:**
  - Unitarios: `IpRangeClassifierTest` (primera y última IP de cada rango, más una IP pública vecina que debe pasar, IPv4 mapeada con sus dieciséis bytes) y `DefaultTargetPolicyTest` con los casos de la tabla y un resolver falso: IP mixtas pública y privada, NAT64 de la metadata, nombre que no resuelve, resolver lento (responde en menos de 1 s con un plazo de 200 ms), límite de 32 resoluciones a la vez, y que el detalle del `422` nunca incluye una dirección. `CidrTest` y `EgressPropertiesTest` (un bloque inválido o un plazo de cero impiden arrancar).
  - Seguridad: metadata siempre bloqueada aunque `allowed-private-cidrs` abra `0.0.0.0/0` y `::/0` (caso 25), en el clasificador y en la política. El caso 24 (prohibida en `production`) ya lo cubre `DeploymentGuardrailsTest`.
- **Security considerations:** código crítico (T-20). Requiere revisión explícita contra el threat model. La validación al guardar da retroalimentación, pero **no es la barrera definitiva**: la definitiva es la resolución con IP fijada de OW-024. El detalle del `422` dice qué regla falla (esquema, puerto, credenciales, host no permitido) pero nunca las IP resueltas: no se convierte en un oráculo del DNS interno del servidor. Resolver un nombre es I/O externo: quien llama a `TargetPolicy` lo hace fuera de una transacción (OW-021).
- **Dependencies:** Sprint 0.
- **Definition of Done:** cada caso de la tabla de SSRF que se decide al guardar tiene su test; la capa 1 del documento de SSRF describe la normalización, el plazo y el nombre que no resuelve.

### OW-021 · Monitores: crear, consultar, editar y cuotas
`feature` · P2 · Milestone: v0.2.0 — Proyectos y monitores · **Hecha**

- **Context:** la configuración de qué vigilar ([modelo de dominio](../architecture/domain-model.md#monitor)). Primer código del módulo `monitoring`. Los headers cifrados llegan en OW-022, y la pausa, la reanudación y el borrado en OW-044. No hay motor todavía: `monitor_state` se crea con cada monitor, pero nadie lo ejecuta hasta la v0.3.0.
- **Objective:** `POST /api/v1/projects/{projectId}/monitors`, `GET /api/v1/projects/{projectId}/monitors`, `GET /api/v1/projects/{projectId}/monitors/summary`, `GET /api/v1/monitors/{monitorId}` y `PATCH /api/v1/monitors/{monitorId}`, con `monitor_state` inicializado.
- **Tasks:**
  - [x] Migración `V6__monitoring_create_monitors_and_state` con el [DDL](../database/database-design.md#9-ddl-preliminar), sin `request_headers` (OW-022) ni `monitor_checks` (OW-027). No hay columna `enabled`: un monitor está pausado cuando su estado es `PAUSED`. La entidad `MonitorState` solo mapea las columnas que se leen o escriben hoy; las del último resultado llegan con el motor.
  - [x] Entidad `Monitor` con sus invariantes (rangos, timeout menor que el intervalo, umbral de degradación hasta `timeoutMs`), comprobadas sobre el estado **resultante** de cada cambio: un `PATCH` que solo baja `intervalSeconds` por debajo de `timeoutMs` → `400`. El accesor de la versión es `savedVersion()`. Las invariantes viven en el record `MonitorSettings`, que se construye entero con cada cambio (`SettingsChanges.applyTo`) y da un `400 validation-error` sobre el campo que las rompe, con los códigos `not-below-interval`, `above-timeout` y `min-above-max`.
  - [x] `MonitorService.create`: autoriza con `requireForProject` (`MONITOR_WRITE`), valida la URL con `TargetPolicy` **fuera de una transacción** y después, en una transacción corta, vuelve a autorizar, bloquea el proyecto con `ProjectDirectory.lockActive`, comprueba la cuota e inserta el monitor y su estado (`PENDING`, `next_check_at` con jitter inicial, `InitialJitter` con un `RandomGenerator` inyectable).
  - [x] `MonitorService.update`: el mismo patrón (autorizar, validar fuera de la transacción, y en la transacción volver a autorizar y comprobar `If-Match`). Cambiar el intervalo reprograma `next_check_at = min(next_check_at, now + intervalo)` con la fila de `monitor_state` bloqueada (`FOR UPDATE`), salvo si el monitor está pausado, que sigue sin programar. Sin el bloqueo, una pausa concurrente acabaría en `PAUSED` con `next_check_at` programado, que `ck_monitor_state_paused` rechaza con un `500`. **Cambiado durante la implementación:** la URL se valida siempre que el `PATCH` la trae, no solo si cambia; comparar con la guardada fuera de la transacción dejaba una carrera con otro `PATCH`. La fila de `monitor_state` se bloquea antes de escribir la de `monitors`, el orden que seguirán la pausa y el borrado de OW-044, para que no haya interbloqueos.
  - [x] Cuota `opswatch.limits.monitors-per-organization` (50) con su propio espacio de advisory lock y sus propias propiedades en `monitoring` (`MonitorLimits`). Los espacios de los advisory locks pasan a un único sitio en `shared`: `shared.lock.LockSpace` (`ORGANIZATIONS_OWNED_BY_USER = 1`, `MONITORS_OF_ORGANIZATION = 2`) y `AdvisoryLocks.lock(space, key)`, con `Propagation.MANDATORY`. Sustituye a `OrganizationRepository.lockQuotaOf` y su `QUOTA_LOCK_NAMESPACE`; el hash de la clave no cambia.
  - [x] `degradedThresholdMs: null` en `PATCH` lo desactiva, con el tipo de tres estados de OW-019. Los demás campos no admiten `null` (`NotNullIfPresent`), tampoco los extremos de `expectedStatus`, que se cambian por separado.
  - [x] Listado con filtros `status` y `q` (nombre, sin distinguir mayúsculas, con `%`, `_` y `\` escapados y de 100 caracteres como máximo) y `sort` por `name` (por defecto), `status` o `createdAt`. La consulta une `monitors` y `monitor_state` y devuelve `MonitorWithState`; `sort=status` ordena por el código del estado, en orden alfabético.
  - [x] `GET /api/v1/projects/{projectId}/monitors/summary` (`MONITOR_READ`): monitores no borrados del proyecto por estado, con todos los estados aunque valgan 0. Sustituye al `monitorCounts` que el catálogo ponía en el proyecto, que obligaba a `organization` a depender de `monitoring`.
  - [x] `ETag` en `POST`, `GET` y `PATCH`, e `If-Match` opcional en `PATCH`.
  - [x] Filas nuevas en la matriz de autorización, con un monitor en su `Fixture` y la URL resuelta por el resolver falso de OW-020. **Cambiado durante la implementación:** ese resolver era una clase anidada de `DefaultTargetPolicyTest`; pasa a `FakeHostResolver` (test, `egress.internal`) y `@IntegrationTest` lo pone en lugar del DNS del sistema con `TestHostResolver`, así que ningún test de integración depende del DNS real.
  - [x] Añadido durante la implementación: un monitor se autoriza sobre su proyecto (`requireForProject`), no con `require` sobre su organización. Un monitor de un proyecto borrado da `404` aunque la limpieza de OW-044 todavía no lo haya borrado, y el `404` habla del monitor, nunca del proyecto ni de la organización.
- **Acceptance Criteria:**
  - Un `MEMBER` crea `GET https://api.example.com/health` cada 60 s; un `VIEWER` recibe `403`; un no miembro, `404`.
  - Una URL de la tabla de SSRF → `422 target-not-allowed`, también en `PATCH`.
  - El monitor 51 de una organización → `422 quota-exceeded`, también con creaciones simultáneas.
  - Un nombre duplicado en el mismo proyecto → `409`.
  - `timeoutMs` mayor o igual que el intervalo → `400`, tanto al crear como al cambiar uno solo de los dos en `PATCH`.
  - `sort=url` (fuera de la lista blanca) → `400 invalid-parameter`.
  - Un monitor creado mientras se borra su proyecto: o la creación da `404`, o el monitor existe antes de que se publique `ProjectDeleted` (nunca queda un monitor vivo en un proyecto borrado).
- **Testing:**
  - Unitarios: invariantes de `Monitor` (también tras cada cambio parcial) y cálculo del jitter con `RandomGenerator` fijo. Están en `MonitorSettingsTest`, `SettingsChangesTest`, `MonitorTest`, `MonitorStateTest`, `InitialJitterTest` y `NamePatternTest`.
  - Integración (concurrencia): creaciones simultáneas en el límite de la cuota; creación de un monitor contra el borrado concurrente de su proyecto (20 repeticiones). Están en `MonitorConcurrencyIT`, y `AdvisoryLocksIT` prueba los locks por separado. Quitando `lockActive`, la carrera con el borrado falla; quitando el advisory lock, fallan la cuota y la espera; quitando la autorización previa a la resolución, falla el test del DNS de `MonitorApiIT` (comprobado).
  - API y seguridad: endpoints por rol, IDOR, `projectId` u `organizationId` en el cuerpo → `400`, `q` con `%` y `_` busca esos caracteres literalmente. Están en `MonitorApiIT`, que además comprueba con el resolver falso que ni un `VIEWER` ni un no miembro hacen resolver una URL, y crea el ejemplo de CharityLink.
- **Security considerations:** URL validada con `TargetPolicy` (T-20); la ordenación por lista blanca y el `q` parametrizado y escapado evitan inyecciones en la consulta (T-13); la cuota limita el uso del motor contra terceros (T-26). El `projectId` sale de la ruta y el `organizationId` del proyecto cargado, nunca del cuerpo (T-12). La resolución DNS fuera de la transacción evita que un DNS lento, que el usuario puede controlar, retenga conexiones del pool. Se autoriza antes de resolver, para que alguien sin permiso no pueda hacer que el servidor resuelva nombres.
- **Dependencies:** OW-019, OW-020.
- **Definition of Done:** el ejemplo de CharityLink del README, sin headers, se puede crear entero por la API.

### OW-022 · `SecretCipher` y headers cifrados en los monitores
`security` · P1 · Milestone: v0.2.0 — Proyectos y monitores · **Hecha**

- **Context:** los headers de los monitores pueden llevar credenciales de terceros (activo A4). `SecretCipher` estaba en el Sprint 0, pero su primer uso es este. Va después de OW-021 porque cifra una columna de `Monitor`. `scripts/dev-keys.sh` ya genera `secrets/encryption-dev-key` y `DeploymentGuardrails` deja sus reglas para esta issue.
- **Objective:** `SecretCipher` (AES-256-GCM) en `shared.crypto` y headers de monitor cifrados y de solo escritura.
- **Tasks:**
  - [x] `SecretCipher` con `keyId` de 1 byte, nonce aleatorio de 12 bytes (`SecureRandom`) y dato asociado. Formato `keyId ‖ nonce ‖ ciphertext ‖ tag`. Un texto cifrado que no se descifra lanza `DecryptionFailedException` (`500`, sin datos en el mensaje).
  - [x] Claves en `opswatch.security.encryption.keys.<id>` (id de 0 a 255, AES-256 en Base64) y `active-key-id`. La aplicación no arranca en ningún perfil sin la clave activa, ni con una clave que no mida 32 bytes. En `local`, desde `secrets/` con `configtree`, como las claves JWT; en los tests, una clave generada por JVM, como `TestJwtKeys` (`TestEncryptionKeys`). **Cambiado durante la implementación:** las claves se comprueban al construir `SecretCipher` y no al enlazar `EncryptionProperties`, porque Spring Boot informa un fallo de enlace con el valor de la última propiedad enlazada (`BindFailureAnalyzer`), que sería una clave.
  - [x] Migración `V7__monitoring_add_monitor_request_headers` (`request_headers bytea`, nula).
  - [x] Cifrado explícito en `monitoring`, sin `AttributeConverter`: un converter solo recibe la columna y al leer no conoce el id del monitor, que es el dato asociado. La entidad guarda los bytes cifrados y el servicio los cifra y descifra con el dato asociado `monitors.request_headers:<monitorId>` (`MonitorHeaders`, JSON con su propio `JsonMapper`). Una lista vacía es `NULL`. Un `PATCH` solo vuelve a cifrar si la lista descifrada cambia: un texto cifrado distinto en cada cifrado subiría la versión sin motivo.
  - [x] Validación de headers en `egress` (capa 4 de SSRF, para que OW-024 la vuelva a aplicar al enviar): lista de prohibidos (incluidos los de metadata cloud y `Authorization: Bearer Oracle`), sin CR ni LF, gramática de *token* en el nombre, 10 headers y 1024 bytes por valor como máximo. Es `HeaderPolicy.check`, una función pura que devuelve la primera `HeaderViolation`. **Añadido durante la implementación:** nombres de 256 caracteres como máximo y sin repetir; valores solo con ASCII imprimible y tabulador (cubre CR, LF y el resto de caracteres de control, y evita que dos clientes HTTP lean distinto un valor fuera de ASCII); `Bearer Oracle` sin distinguir mayúsculas ni espacios.
  - [x] `headers` en `POST` y `PATCH` (la lista se reemplaza entera; `[]` los quita; `null` → `400`). Respuesta con `value: null` y `hasValue: true`; `toString()` de los DTOs y del tipo de headers sin valores. Un header rechazado da `400 validation-error` sobre su campo (`headers[1].value`), nunca `422`: es un campo del cuerpo, no el destino. Los espacios alrededor de un valor se quitan.
- **Acceptance Criteria:** en la base de datos solo hay texto cifrado; ninguna respuesta ni línea de log contiene el valor; un texto cifrado copiado a otro monitor no se descifra; la aplicación no arranca sin clave; un texto cifrado con una clave antigua que sigue configurada se descifra después de cambiar `active-key-id`; los casos 20 y 21 de la [tabla de SSRF](../security/ssrf-protection.md#5-casos-de-prueba-obligatorios) (headers de metadata y `\r\n`) se rechazan al guardar.
- **Testing:**
  - Unitarios: `SecretCipherTest` (ida y vuelta, tag alterado, dato asociado distinto, `keyId` desconocido, dos cifrados del mismo texto distintos) y validación de headers. Además: rotación de claves, id 255, texto truncado; `HeaderPolicyTest` (casos 20 y 21) y `HeaderInputTest`.
  - Seguridad: forma de la respuesta, captura de logs con un valor centinela y texto cifrado copiado entre dos monitores. Están en `MonitorHeadersApiIT`, con los logs de la aplicación, de Spring MVC y de Hibernate a TRACE. Quitando el id del dato asociado, falla el texto copiado; quitando la redacción de `HeaderInput`, fallan los logs (comprobado).
  - Arranque: `DeploymentGuardrailsTest` y `ApplicationStartupIT` sin clave. **Cambiado durante la implementación:** el arranque sin clave lo prueba `EncryptionPropertiesTest` con un `ApplicationContextRunner`, como `JwtConfigurationTest` con la clave JWT; `ApplicationStartupIT` arranca con la clave de los tests. `DeploymentGuardrails` no necesita reglas nuevas: la clave se exige en todos los perfiles.
- **Security considerations:** T-14. Nunca se reutiliza un nonce con la misma clave: con nonces aleatorios de 12 bytes, el límite práctico es de unos 2³² cifrados por clave, muy lejos del volumen de V1. La rotación está prevista con `keyId`; recifrar lo guardado con una clave retirada queda fuera de V1. La clave solo se lee de un secreto. El dato asociado incluye el propósito además del id, para que un texto cifrado de otra tabla (los canales de la v0.4.0) no se pueda mover aquí.
- **Dependencies:** OW-021.
- **Definition of Done:** la sección de cifrado de la [arquitectura de seguridad](../security/security-architecture.md#cifrado-de-datos-sensibles-en-la-base-de-datos) coincide con la implementación; el ejemplo de CharityLink del README se puede crear con headers.

### OW-044 · Monitores: pausar, reanudar, borrar y limpieza por `ProjectDeleted`
`feature` · P2 · Milestone: v0.2.0 — Proyectos y monitores · **Hecha**

- **Context:** acciones de ciclo de vida del monitor, separadas de OW-021 para que ninguna de las dos issues sea demasiado grande. Es el primer listener asíncrono entre módulos, y usa el registro de OW-034.
- **Objective:** `POST /api/v1/monitors/{monitorId}/pause`, `POST /api/v1/monitors/{monitorId}/resume`, `DELETE /api/v1/monitors/{monitorId}` y el borrado de monitores cuando se borra su proyecto.
- **Tasks:**
  - [x] Pausa: `PAUSED`, `next_check_at = NULL` y contadores a cero; evento `MonitorPaused`.
  - [x] Reanudación: `PENDING`, `next_check_at` con jitter y contadores a cero.
  - [x] Borrado lógico: `deleted_at`, estado como en la pausa; evento `MonitorDeleted`.
  - [x] Las tres bloquean la fila de `monitor_state` (`FOR UPDATE`) antes de escribir. El borrado cambia la fila de `monitors`, así que su `version` sube y un `PATCH` concurrente falla con `409`. **Añadido durante la implementación:** las tres autorizan antes de bloquear (alguien ajeno no retiene el bloqueo) y vuelven a leer el monitor con el bloqueo tomado, así que una reanudación que llega justo después de un borrado da `404` en vez de programar un monitor borrado. El borrado lee además el monitor con `FOR UPDATE`, siempre después del estado: ve la versión que acaba de confirmar un `PATCH` y no falla por ella.
  - [x] Listener asíncrono de `ProjectDeleted` (`@ApplicationModuleListener`, con el registro de OW-034): borra cada monitor no borrado del proyecto por el mismo camino que `DELETE`, uno a uno. Así cada uno publica su `MonitorDeleted` (en OW-032, `incident` resolverá sus incidentes activos) y su `version` sube. **Nada de un `UPDATE` masivo**: no incrementaría `version`, y un `PATCH` concurrente reescribiría todas las columnas del monitor cargado, `deleted_at = NULL` incluido, y lo resucitaría. Es `ProjectDeletedListener` → `MonitorService.deleteAllOf`, que lista solo los ids (`findActiveIdsOfProject`) para no cargar copias que luego estarían viejas en el contexto de persistencia.
  - [x] `MonitorDeleted.deletedBy` es quien borró el monitor o, en la limpieza, el `deletedBy` de `ProjectDeleted`.
  - [x] Añadido durante la implementación: `InitialJitter` usa el bean `RandomGenerator` de `ClockConfiguration`, como pide la estrategia de tests, en lugar de crear el suyo. El bean pasa a ser `java.util.Random`: lo comparten todos los hilos, y `RandomGenerator.getDefault()` no es seguro entre hilos.
- **Acceptance Criteria:**
  - Pausar un monitor pausado → `409`; reanudar uno activo → `409`; un `VIEWER` → `403`.
  - Al borrar un proyecto, todos sus monitores quedan borrados y sin programar, también si la aplicación se reinicia entre el commit y el listener.
  - Un `ProjectDeleted` duplicado no hace nada más.
  - Un `PATCH` concurrente con la limpieza nunca resucita el monitor: o falla con `409`, o el monitor acaba borrado.
- **Testing:**
  - Unitarios: transiciones de pausa y reanudación (`MonitorStateTest`, `MonitorTest`).
  - Módulo: `@ApplicationModuleTest` con `Scenario`: `ProjectDeleted` → monitores borrados y un `MonitorDeleted` por cada uno. Es `MonitoringModuleIT`, con `extraIncludes = "shared"`, porque Spring Modulith filtra las clases de otros módulos aunque se importen una a una. También comprueba un `ProjectDeleted` repetido.
  - Integración: reinicio entre el commit y el listener (con la infraestructura de `EventPublicationRegistryIT`); `PATCH` concurrente con la limpieza (20 repeticiones). El reinicio es `ProjectCleanupRestartIT`: el primer contexto deja el proyecto borrado y su publicación como la escribe Spring Modulith al publicar, y el segundo la ejecuta al arrancar. La carrera está en `MonitorConcurrencyIT`: cambiando la limpieza por un `UPDATE` masivo, el monitor resucita y el test falla (comprobado). La carrera de crear un monitor contra el borrado de su proyecto ahora espera a la limpieza y exige que no quede ningún monitor vivo.
  - API y seguridad: endpoints por rol e IDOR (`MonitorLifecycleApiIT` y tres filas de la matriz, con un monitor pausado en el `Fixture` para `resume`).
- **Security considerations:** integridad: un monitor borrado o pausado no debe seguir haciendo peticiones (`next_check_at = NULL` y la restricción `ck_monitor_state_paused`). El listener es idempotente: solo toca monitores no borrados, así que un `ProjectDeleted` duplicado no hace nada. Entre el borrado del proyecto y la limpieza (normalmente milisegundos) sus monitores siguen en la base sin borrar, pero la API ya da `404` para ellos (desde OW-021 un monitor se autoriza sobre su proyecto) y la limpieza está garantizada por el registro.
- **Dependencies:** OW-021, OW-034.
- **Definition of Done:** los eventos `MonitorPaused` y `MonitorDeleted` están documentados; sus listeners en `incident` llegan en OW-032.

### OW-023 · Tests de aislamiento multi-tenant de la Fase 2
`testing` `security` · P2 · Milestone: v0.2.0 — Proyectos y monitores · **Cerrada**

Fusionada en OW-019, OW-021 y OW-044: los tests de IDOR de cada endpoint forman parte de la [Definition of Done](definition-of-done.md) de la issue que crea el endpoint, y OW-018 hace imposible olvidarlos.

---

## v0.3.0 — Motor de monitoreo

Orden: OW-024 → OW-025 → OW-027 → OW-026 → OW-028 → OW-029 → OW-030. OW-027 va antes que OW-026 (decisión de Ricardo del 2026-10-03): el scheduler es lo último y une el cliente con el registro de resultados, así que en `main` nunca hay un motor que haga peticiones y pierda lo que observa.

Refinada el 2026-10-03 contra lo que dejó construido la v0.2.0:
- en `egress`, `TargetPolicy` y `HeaderPolicy`, con `HostResolver` inyectable e `IpRangeClassifier`;
- `monitor_state` con su orden de bloqueos: la fila del estado antes que la del monitor;
- `MonitorHeaders` para descifrar los headers;
- el bean `RandomGenerator` del jitter y el registro de eventos;
- en los tests, `FakeHostResolver` y `TestHostResolver`.

### OW-024 · `GuardedDnsResolver` y cliente HTTP saliente endurecido
`security` · P1 · Milestone: v0.3.0 — Motor de monitoreo · **Hecha**

- **Context:** capas 2 a 5 de la protección SSRF: la defensa contra el DNS rebinding. Ya existen piezas en las que se apoya:
  - de OW-020, `HostResolver` (inyectable, con `FakeHostResolver` en los tests), `IpRangeClassifier` con `allowed-private-cidrs` y `TargetUrlParser`;
  - de OW-022, `HeaderPolicy`, que esta issue vuelve a aplicar al enviar.
- **Objective:** `EgressHttpClients` construye clientes Apache HttpClient 5 con el resolver protegido y todas las restricciones; nadie más construye clientes HTTP.
- **Tasks:**
  - [x] Dependencia `org.apache.httpcomponents.client5:httpclient5` sin versión propia: la fija el BOM de Spring Boot 4.1.1 (5.6.4). Decisión de Ricardo del 2026-10-03.
  - [x] `GuardedDnsResolver` (el `DnsResolver` de HttpClient):
    - resuelve con el `HostResolver` de OW-020 y clasifica **cada** dirección con `IpRangeClassifier`;
    - si alguna está bloqueada, lanza `BlockedTargetException`;
    - devuelve solo direcciones validadas, así que el cliente conecta exactamente a ellas;
    - sin plazo propio, porque la resolución del sistema no se puede interrumpir (sección DNS del documento del motor).
    - **Añadido durante la implementación:** una dirección literal se clasifica tal cual, sin preguntar a ningún resolver.
  - [x] Las reglas de `TargetUrlParser` sin resolver el nombre (esquema, forma del host, puerto y credenciales), antes de cada petición y en cada redirect, con `BlockedTargetException` y nunca el `422` de la API.
    - **Cambiado durante la implementación:** no hay un `TargetPolicy.validateSyntax` público que cada llamador tenga que recordar. La comprobación va dentro del cliente, en `EgressRequestGuard`, y ninguna petición se la salta, tampoco cada salto de un redirect.
    - Es el primer eslabón de la cadena de ejecución (`addExecInterceptorFirst`), antes del DNS y de conectar. Un `HttpRequestInterceptor` no servía: en httpclient5 5.x los ejecuta `MainClientExec` **después** de `ConnectExec`, así que el interceptor llegaba con la conexión ya abierta (lo destapó el test del puerto 22).
    - Un esquema que no es http ni https, o credenciales en la URL, los rechaza el propio cliente antes de la cadena, con `ClientProtocolException`. Tampoco salen ni resuelven nada.
  - [x] `HeaderPolicy.check` antes de cada petición: un header guardado antes de que existiera una regla, o escrito en la base por fuera de la API, nunca sale (`BlockedTargetException`). Va en el mismo `EgressRequestGuard`.
  - [x] Cliente sin proxy (ni el del entorno ni uno configurado), sin reintentos, sin redirects automáticos, sin cookies, sin compresión, sin cache de autenticación y sin reutilizar conexiones; límites de línea (8 KiB) y número (100) de headers de respuesta; TLS 1.2 o superior.
    - **Cambiado durante la implementación:** el timeout de conexión no se puede fijar por petición (en httpclient5 5.x ese ajuste está deprecado en `RequestConfig` y el build trata los avisos como errores). El cliente se crea con el timeout máximo (`EgressClientSettings.timeout`). Cada petición puede bajar el de la respuesta, y el plazo de cada check lo corta el deadline de OW-025.
  - [x] Regla ArchUnit: solo `egress` construye clientes HTTP (`HttpClients`, `HttpClientBuilder`, sus equivalentes asíncronos, `java.net.http.HttpClient`, `RestClient`, `RestTemplate`, `WebClient` y `URL.openConnection`).
  - [x] Evento de seguridad `egress.target_blocked` en el log, con el host y nunca la URL completa (la query puede llevar un token). La métrica `opswatch_egress_blocked_total` llega con OW-030.
- **Acceptance Criteria:**
  - Los casos 16, 17, 22 y 23 de la [tabla de SSRF](../security/ssrf-protection.md#5-casos-de-prueba-obligatorios).
    - **Matiz:** del caso 22, aquí se prueba el destino que no contesta, que se corta con el timeout de respuesta. El que gotea bytes necesita el deadline sobre la petición entera, que es de OW-025.
  - Una URL con IP literal pasa por `resolve()` (test).
  - Una clase de `monitoring` que instancia `HttpClients` hace fallar el build.
  - Un header `Metadata-Flavor` insertado en la base sin pasar por la API no sale. Aquí se prueba en el cliente; el camino desde la base lo recorrerá OW-026.
- **Testing:**
  - Unitarios: `GuardedDnsResolverTest` con `FakeHostResolver` (IP mixtas, rebinding: pública al guardar y privada al conectar, y literales sin pasar por el resolver).
  - Integración: `EgressHttpClientsTest` contra WireMock en `127.0.0.1`:
    - casos 16, 17 y 23;
    - la IP literal pasa por el resolver;
    - URL y headers comprobados otra vez antes de salir, sin DNS;
    - el proxy del entorno se ignora;
    - sin reintentos, redirects, cookies ni `Accept-Encoding`;
    - destino que no contesta;
    - `User-Agent`.
    - WireMock es `org.wiremock:wiremock-standalone` 3.13.2, solo en test: viene sombreado y no choca con Tomcat 11 (decisión de Ricardo del 2026-10-03).
    - Escucha en `127.0.0.1`, así que esos tests abren `opswatch.egress.allowed-private-cidrs=127.0.0.0/8`, el uso para el que existe la propiedad.
  - Arquitectura: la regla ArchUnit, y un test que comprueba que una clase de `monitoring` (`HandMadeHttpClient`, en los tests) la incumple.
  - Quitando `EgressRequestGuard` de la cadena, fallan los seis tests de URL y headers (comprobado).
- **Security considerations:** T-20, T-21 y T-23. Un proxy del entorno saltaría el resolver: por eso se desactivan las propiedades del sistema. Si una versión futura del cliente dejara de pasar por el resolver, el test de la IP literal lo detecta. Volver a validar al enviar lo que se validó al guardar no es redundante: las reglas cambian entre versiones, y la base se puede tocar por fuera de la API.
- **Dependencies:** OW-020, OW-022.
- **Definition of Done:** el documento de SSRF coincide con la implementación.

### OW-025 · `HttpMonitorClient` con Apache HttpClient 5
`feature` `security` · P1 · Milestone: v0.3.0 — Motor de monitoreo · **Hecha**

- **Context:** separar observar de juzgar ([motor](../architecture/monitoring-engine.md#5-httpmonitorclient)). Los headers llegan descifrados por `MonitorHeaders` (OW-022) como `RequestHeader` de `egress`. De OW-024 llega `EgressHttpClients`: el cliente ya comprueba la URL y los headers de cada petición antes de salir, y resuelve con el resolver protegido.
- **Objective:** `ApacheHttpMonitorClient` con deadline total, redirects manuales y clasificación de fallos.
- **Tasks:**
  - [x] `ProbeRequest`, con `List<RequestHeader>` y un `toString()` sin valores, y `HttpObservation` (sealed).
    - **Añadido durante la implementación:** el `toString()` tampoco imprime las credenciales ni la query de la URL, que puede llevar un token.
  - [x] `FailureReason` en el paquete raíz de `monitoring`: aparece en la firma de `MonitorWentDown` ([eventos](../architecture/events.md#2-catálogo)).
  - [x] Un cliente de `EgressHttpClients` (`TargetKind.MONITOR`, `max-concurrent-checks` conexiones, el timeout máximo de un monitor) creado una vez y cerrado al apagar.
    - `MonitoringEngineProperties` (`opswatch.monitoring.engine`) nace aquí con `max-concurrent-checks`, `max-redirects`, `deadline-grace` y `user-agent`. Las demás propiedades del motor llegan con OW-026.
  - [x] Deadline total con `cancel()` programado: `timeoutMs` más `opswatch.monitoring.engine.deadline-grace` (200 ms). También acota la conexión, cuyo timeout no se puede fijar por petición (OW-024).
    - Un hilo de plataforma (`check-deadline`) programa el corte de todos los checks. `cancel()` cierra el socket en el acto, también a mitad de un `connect`: httpclient5 5.6.4 ata el socket a la conexión antes de conectar (comprobado en su bytecode). Ese caso no tiene test: un `connect` que no termina no se simula igual en Windows y en Linux.
    - **Cambiado durante la implementación:** las peticiones no bajan el timeout de respuesta. Un `RequestConfig` por petición sustituye entero al del cliente de `egress`, y con él la espera máxima del pool. El deadline es el único plazo de cada check.
  - [x] Redirects manuales:
    - cada salto es una petición nueva del mismo cliente, así que `EgressRequestGuard` y el resolver protegido lo comprueban sin nada más;
    - cambio de método según el código. **Cambiado durante la implementación:** con `GET` y `HEAD` el método nunca cambia (`301`, `302` y `303` pasan a `GET` todo menos `HEAD`, y `307` y `308` lo conservan), así que no hay código que lo cambie;
    - headers solo al mismo origen (esquema, host y puerto) que la URL del monitor;
    - `opswatch.monitoring.engine.max-redirects` (5) como máximo y detección de bucles;
    - el `Location` se resuelve con `URIUtils.resolve` de httpclient5, que sigue la RFC 3986 (`URI.resolve` no lo hace con una referencia que solo trae query), y sin fragmento.
  - [x] Mapeo de excepción a `FailureReason`: `BlockedTargetException` → `TARGET_BLOCKED`, y también la `ClientProtocolException` con la que el cliente rechaza un esquema o unas credenciales en la URL (solo pueden venir de la base tocada por fuera de la API).
    - **Cambiado durante la implementación:** `ClientProtocolException` no basta para distinguirlas. El cliente también la usa para una respuesta que no puede leer (un header sin `:`, `HTTP/2.0` en la línea de estado). `ApacheHttpMonitorClient` reconoce antes de enviar lo que el cliente rechazaría (esquema, URL sin host, credenciales) y lo clasifica `TARGET_BLOCKED`; el resto de `ClientProtocolException` es `PROTOCOL_ERROR`. Sin ese paso, esas URL tampoco salen, pero contarían como un fallo del destino.
    - Una vez vencido el deadline, cualquier excepción del cliente es el corte: `TIMEOUT`.
  - [x] Cierre de la conexión tras recibir los headers: el cuerpo no se lee.
    - Cerrar la respuesta leería el cuerpo hasta el final, así que antes se cancela la petición, que cierra el socket.
  - [x] `User-Agent` de `opswatch.monitoring.engine.user-agent`.
    - El valor por defecto vive en `application.yml`, con la versión del pom que pone Maven al copiar los recursos: la de la release en un tag y `X.Y.0-SNAPSHOT` entre releases ([versionado](../development/versioning.md#aplicación-semantic-versioning)).
- **Acceptance Criteria:**
  - Los casos 18, 19 y 22 de la tabla de SSRF; el 22 con un destino que gotea bytes (un servidor de sockets en el test: WireMock no gotea headers).
  - Un destino que tarda más que `timeoutMs` devuelve `TIMEOUT` en menos de `timeoutMs` + 500 ms.
  - Cada fila de la [tabla de clasificación](../architecture/monitoring-engine.md#8-clasificación-de-fallos) produce su `FailureReason`.
  - `error_detail` es siempre un texto propio y genérico: las constantes de `Failures`.
- **Testing:**
  - Integración: `ApacheHttpMonitorClientTest` contra WireMock en `127.0.0.1` (códigos, retrasos, redirects, bucles, más de 5 saltos, TLS autofirmado, `Location` inválido).
    - **Cambiado durante la implementación:** se llama `*Test` y no `*IT`, como `EgressHttpClientsTest`: WireMock corre dentro del proceso y no necesita Docker.
    - Desde `monitoring`, el `EgressHttpClients` real lo da `TestEgressHttpClients` (tests, `egress.internal`).
    - El caso 22 usa un servidor de sockets propio que manda un byte de header cada 100 ms.
  - Seguridad:
    - redirect a otro origen sin `Authorization`;
    - redirect hacia `169.254.169.254`;
    - redirect a un puerto o un esquema que el cliente rechaza.
  - Unitarios: `FailuresTest` con cada fila de la tabla, también las que un destino simulado no produce de forma fiable (sin ruta, certificado caducado), y comprobando que el detalle nunca repite el mensaje de la excepción; `RedirectsTest`, `DeadlineTest`, `ProbeRequestTest` y `MonitoringEnginePropertiesTest`.
  - Comprobado que los tests detectan el fallo: sin el deadline, sin el reconocimiento de lo que rechaza el cliente o sin la regla del mismo origen, fallan.
  - `MonitoringModuleIT` (`@ApplicationModuleTest`, sin el módulo `egress`) simula `EgressHttpClients` con `@MockitoBean(answers = RETURNS_MOCKS)`: `create()` devuelve un cliente simulado, y `ApacheHttpMonitorClient` arranca y se cierra sin red.
- **Security considerations:** T-22 (redirect hacia la red interna), T-27 (credenciales reenviadas a otro host) y T-28 (el cuerpo no se guarda ni se muestra). Disponibilidad: el deadline impide que un destino hostil retenga un permiso más de `timeoutMs`.
- **Dependencies:** OW-024.
- **Definition of Done:** la tabla de clasificación del documento del motor está verificada por los tests.

### OW-027 · Registro de resultados y máquina de estados del monitor
`feature` · P1 · Milestone: v0.3.0 — Motor de monitoreo · **Hecha**

- **Context:** la [tabla de transiciones](../architecture/domain-model.md#monitorstate) es el núcleo de la correctitud. Incluye la carrera de la pausa que antes estaba en OW-031.
  - De OW-021 y OW-044 ya existen `MonitorState` (con `pause`, `resume` y `stop`), su bloqueo `findByIdForUpdate` y el orden de bloqueos: la fila del estado antes que la del monitor.
  - Va antes que OW-026 (decisión de Ricardo del 2026-10-03): se puede llamar directamente con un resultado, y el scheduler la usará.
- **Objective:** `CheckEvaluator`, `StateTransition` y `CheckResultRecorder`, con los eventos `MonitorWentDown` y `MonitorRecovered`.
- **Tasks:**
  - [x] Migración `monitoring_create_monitor_checks` (la `V8`), con PK `(monitor_id, checked_at)` y BRIN sobre `checked_at`.
  - [x] `MonitorState` mapea las columnas del último resultado que OW-021 dejó sin mapear (`last_check_status` y `last_failure_reason`).
  - [x] `CheckEvaluator` y `StateTransition` como funciones puras.
    - **Cambiado durante la implementación:** `CheckEvaluator` vive en `monitoring.engine`, junto a `HttpObservation`, y no en `domain`, que no depende del motor.
    - **Añadido durante la implementación:** una respuesta que llega después de `timeoutMs` (dentro del margen del deadline) es `DOWN` por `TIMEOUT`, con su código y su latencia: es lo que dice la definición de `TIMEOUT`.
  - [x] `CheckResultRecorder`:
    - toma `FOR UPDATE` sobre el estado y nunca sobre la fila de `monitors`: un check no cambia la configuración, ni su versión;
    - inserta el check con `JdbcClient` (`MonitorCheckRepository`), aplica la transición y publica el evento;
    - con el estado `PAUSED` (también el de un monitor borrado mientras el check estaba en vuelo), guarda el check sin transición;
    - **añadido durante la implementación:** tampoco hay transición para un check que empezó antes de `status_changed_at`. Es uno que estuvo en vuelo durante una pausa y una reanudación: pertenece al monitor de antes, y con un umbral de 1 abriría un incidente para un monitor que nadie ha comprobado desde que se reanudó;
    - el instante de una transición, `checked_at`, `last_checked_at` y el `occurredAt` de los eventos son el inicio del check, truncado a microsegundos.
    - Recibe un `MonitorSnapshot` (ids, nombre y `MonitorSettings` del monitor, leídos al reclamar el check) que construirá OW-026.
  - [x] `MonitorWentDown` y `MonitorRecovered` en el paquete raíz, con ids y sin datos personales. Nadie los escucha hasta OW-032.
  - [x] Los errores internos no cuentan como check: una `RuntimeException` propia, y también una `DecryptionFailedException` al descifrar los headers (una clave retirada antes de tiempo). Dejan un log de error con el id del monitor, sin check y sin transición.
    - Aquí, los de la transacción del resultado: la base de datos o un listener que lanza. `CheckResultRecorder.record` nunca lanza; lo revierte todo, lo registra en el log y lo cuenta en `opswatch.monitor.checks{outcome="ERROR"}`.
    - **Cambiado durante la implementación:** los errores de la petición y del descifrado ocurren antes de llegar aquí, y los trata OW-026 con el mismo contador.
    - El contador `opswatch.monitor.checks` (`opswatch_monitor_checks_total` en Prometheus) nace aquí, con `outcome` y `reason` (`NONE` si no hay `FailureReason`).
- **Acceptance Criteria:**
  - 3 fallos consecutivos → `DOWN` y un `MonitorWentDown`; 2 éxitos → `UP` y un `MonitorRecovered`.
  - Pausa concurrente con el registro de un resultado (50 repeticiones): el estado final es siempre `PAUSED` y el check queda guardado.
  - El resultado de un monitor borrado mientras su check estaba en vuelo se guarda, y el estado sigue `PAUSED` y sin programar.
  - Una `RuntimeException` propia no crea check ni cambia el estado, y cuenta en `outcome="ERROR"`.
- **Testing:**
  - Unitarios: `StateTransitionTest` con cada fila de la tabla y `CheckEvaluatorTest`; también `CheckOutcomeTest` y los casos nuevos de `MonitorStateTest`.
  - Integración (concurrencia): `CheckResultRecorderIT`, incluida la carrera de la pausa.
    - **Añadido durante la implementación:** la carrera de 50 repeticiones no detecta que falte el `FOR UPDATE` (comprobado quitándolo): la ventana de la actualización perdida es muy estrecha. Un segundo test la fuerza: una pausa retiene la fila, el resultado tiene que esperarla y no cambia nada.
    - El error interno se provoca con un listener que lanza en `MonitorWentDown`, añadido y quitado en el mismo test sobre el contexto compartido.
  - Comprobado que los tests detectan el fallo: sin el `FOR UPDATE` o sin la regla del check anterior a la reanudación, fallan.
- **Security considerations:** integridad del estado: `FOR UPDATE` serializa las transiciones de cada monitor. `error_detail` es un texto propio, nunca contenido de la respuesta (T-28).
- **Dependencies:** OW-025, OW-044.
- **Definition of Done:** cada fila de la tabla de transiciones del modelo de dominio tiene su test.

### OW-026 · Scheduler con `SKIP LOCKED` y dispatcher con virtual threads
`feature` `architecture` · P1 · Milestone: v0.3.0 — Motor de monitoreo · **Hecha**

- **Context:** [ADR-006](../adr/ADR-006-check-scheduling.md) y [ADR-007](../adr/ADR-007-http-client-and-concurrency.md). Une el cliente (OW-025) con el registro (OW-027), por eso va después de los dos. Incluye los tests de concurrencia que antes estaban en OW-031.
  - El claim toma la fila de `monitor_state` con `FOR UPDATE SKIP LOCKED`: una pausa, una reanudación o un borrado en curso la tienen bloqueada, y el claim la salta.
  - Después, una pausa o un borrado la dejan sin programar.
- **Objective:** `CheckClaimer` y `CheckDispatcher` con semáforo, sin catch-up y con apagado ordenado.
- **Tasks:**
  - [x] Consulta de claim (CTE con `FOR UPDATE SKIP LOCKED`). Exige además `monitors.deleted_at IS NULL` como defensa: un monitor borrado ya no está programado.
    - **Cambiado durante la implementación:** el CTE une `monitors` para el filtro y el intervalo, así que el bloqueo es `FOR UPDATE OF s SKIP LOCKED`, solo sobre la fila del estado. Sin el `OF s` bloquearía también la fila del monitor, y un `PATCH` esperaría al claim. La fila del monitor se lee sin bloquear: todo el que cambia su configuración bloquea antes la del estado, y el claim la salta.
  - [x] En la misma transacción corta, carga de la configuración y descifrado de los headers (`MonitorHeaders`). La petición HTTP ocurre fuera de toda transacción.
    - `MonitorHeaders` y su `unseal` pasan a ser públicos: los usa `monitoring.engine`.
  - [x] Dispatcher con `Semaphore` y executor de virtual threads; reclama como mucho los permisos libres.
    - Sin permisos libres no llama al claim. Un claim que falla (la base de datos) deja un log de error y el siguiente dispatch lo intenta de nuevo.
  - [x] Ejecución de cada check (de OW-025 y OW-027): el claim construye un `MonitorSnapshot` y el `ProbeRequest`; el hilo hace `HttpMonitorClient.probe`, `CheckEvaluator.evaluate` y `CheckResultRecorder.record`.
    - Una `RuntimeException` de la petición, o una `DecryptionFailedException` al descifrar los headers de un monitor, no es un check: log de error con el id del monitor y `opswatch.monitor.checks{outcome="ERROR"}`, el mismo contador que usa `CheckResultRecorder`. Un fallo de descifrado afecta a ese monitor, nunca al lote reclamado.
    - **Cambiado durante la implementación:** el log y el contador viven en `CheckResultRecorder.recordError`, que también usa su propio `record`. El claim trata igual cualquier `RuntimeException` al construir la petición de un monitor (no solo el descifrado), y la cuenta después del commit: un claim revertido no reclamó nada. El monitor queda reclamado, así que espera a su siguiente intervalo.
  - [x] `opswatch.monitoring.engine.enabled`, `true` por defecto y `false` en el perfil `test`.
    - Sin eso, el contexto compartido de los `@IntegrationTest` haría peticiones reales a `api.example.com` por cada monitor de los tests (el DNS falso lo resuelve a una IP pública).
    - Los tests del motor lo activan en su propio contexto.
    - Con él llegan `dispatch-interval`, `max-batch-size` y `shutdown-grace`, con los valores del catálogo. `overdue-threshold` llega con sus métricas (OW-030).
  - [x] Apagado ordenado (`SmartLifecycle`): deja de reclamar y espera como mucho `timeoutMs` más `opswatch.monitoring.engine.shutdown-grace`.
    - Lo que siga en vuelo después no se guarda. `ApacheHttpMonitorClient` cierra su cliente al apagarse (OW-025), y una petición cortada así llegaría como `CONNECTION_FAILED`: un fallo propio contado como del destino, que podría abrir un incidente falso.
    - **Concretado durante la implementación:** espera hasta el deadline más lejano de los checks en vuelo (`timeoutMs` más `deadline-grace` desde que se lanzaron) más `shutdown-grace`, en un hilo propio. Va en la fase del apagado ordenado del servidor web, así los dos esperan a la vez dentro de los 35 s de `timeout-per-shutdown-phase` y de los 40 s de `stop_grace_period`. Un check abandonado se reconoce porque su executor está cerrado; si la fase se agota antes, el `destroy` del dispatcher lo cierra, y Spring lo destruye antes que al cliente, del que depende.
- **Acceptance Criteria:**
  - 4 claimers en paralelo sobre 1 000 monitores vencidos, 20 rondas: cada monitor reclamado **exactamente una vez** por ronda (0 duplicados, 0 omitidos).
  - Un monitor atrasado más de un intervalo se ejecuta una vez y salta al siguiente intervalo (sin catch-up).
  - Con el semáforo lleno no se reclama nada.
  - Un monitor pausado o borrado durante el claim no se ejecuta.
  - El plan de la consulta de claim usa `ix_monitor_state_due`.
- **Testing:**
  - Unitarios: cálculo del siguiente `next_check_at` con `Clock` fijo.
    - **Cambiado durante la implementación:** el cálculo vive en la consulta de claim, así que se prueba en `CheckClaimerIT` con el instante del claim fijado. `CheckDispatcherTest` (unitario, con el claim simulado) prueba cuánto pide el dispatcher y cuándo no pide nada.
  - Integración (concurrencia): `CheckClaimerConcurrencyIT` con PostgreSQL real.
  - Integración: dispatcher con un `HttpMonitorClient` falso; `EXPLAIN` del claim.
    - `CheckDispatcherIT` construye cada dispatcher en el test, con el claim y el registro reales, y llama a `dispatch()`. `MonitoringEngineIT` activa el motor en su propio contexto (`@DirtiesContext`) y comprueba que un monitor vencido se ejecuta solo.
    - **Añadido durante la implementación:** los tests del motor reclaman en 2001 (`PastSchedule`). Comparten la base con todos los demás, que programan sus monitores a la hora real, y así solo ven los suyos; antes y después de cada test se desprograma todo lo anterior a 2002.
  - Comprobado que los tests detectan el fallo: sin el `FOR UPDATE` hay duplicados; sin `SKIP LOCKED` el claim espera a la pausa; con catch-up, sin el filtro de borrados o sin descartar los checks abandonados, fallan.
- **Security considerations:** una ejecución duplicada no es solo un fallo de correctitud: duplica el tráfico hacia terceros (T-26) y puede abrir incidentes falsos. El semáforo evita agotar hilos y conexiones (R-11, R-12). Los headers descifrados viven solo en memoria durante el check.
- **Dependencies:** OW-025, OW-027.
- **Definition of Done:** el algoritmo del documento del motor coincide con la implementación.

### OW-028 · Consulta de checks (cursor) y estadísticas
`feature` · P2 · Milestone: v0.3.0 — Motor de monitoreo · **Hecha**

- **Context:** historial y uptime por la API.
  - De la v0.1.0 existen `PageQuery` y `PageResponse` (paginación por offset); el cursor es nuevo.
  - De OW-021, la autorización de un monitor sobre su proyecto (`404` también para el monitor de un proyecto borrado).
- **Objective:** `GET /api/v1/monitors/{monitorId}/checks` y `GET /api/v1/monitors/{monitorId}/stats`.
- **Tasks:**
  - [x] `CursorPage` en `shared.web`, con un cursor opaco validado.
    - **Añadido durante la implementación:** `CursorQuery` y su `CursorQueryArgumentResolver`, como `PageQuery`: leen `limit` (50 por defecto, de 1 a 200) y `cursor`, y dan `400 invalid-parameter` si no valen. El cursor (`TimeCursor`) es Base64URL de `{"c":"<instante>"}` y solo se acepta exactamente lo que se escribe. La consulta pide `limit + 1` filas: la de más solo dice que hay otra página, y `nextCursor` es `null` en la última.
    - Filtros del historial: `status` (varios separados por comas), `from` (inclusivo) y `to` (exclusivo). `from` que no es anterior a `to` → `400 invalid-parameter`.
  - [x] Consulta de estadísticas (`FILTER` y `percentile_cont`).
    - **Cambiado durante la implementación:** dos consultas (los totales y los fallos por causa) en una transacción `REPEATABLE READ`, para que un check registrado entre las dos no cuente en una sí y en la otra no. Los tiempos (`avg`, `p50`, `p95` y `p99`) se redondean a milisegundos enteros en PostgreSQL y son `null` si ningún check tuvo respuesta.
  - [x] Ventanas `24h`, `7d` y `30d`. El uptime solo cuenta los checks hechos, así que el tiempo en pausa no suma ni resta.
    - **Añadido durante la implementación:** sin `window`, `24h`.
  - [x] Filas nuevas en la matriz de autorización (`MONITOR_READ`).
    - **Cambiado durante la implementación:** la autorización de un monitor por su id sale de `MonitorService` a `MonitorAccess`, que usan también `MonitorStatsQueries`: un solo sitio decide el `404` que habla del monitor.
- **Acceptance Criteria:**
  - Con datos conocidos, el uptime y los percentiles coinciden con los calculados a mano.
  - Un cursor manipulado → `400`; `limit=201` → `400`; una ventana fuera de la lista → `400 invalid-parameter`.
- **Testing:**
  - Unitarios: codificación del cursor (`CursorPageTest`).
  - Integración: `MonitorStatsIT` con datos sembrados, incluidos 30 días a 30 s (86 400 filas).
  - API y seguridad: IDOR en los dos endpoints (`CheckApiIT`). Un cursor del monitor de otra organización, usado en el propio, solo devuelve checks del propio.
  - Comprobado que los tests detectan el fallo: con el cursor ignorado, con `to` inclusivo o sin autorizar, fallan.
- **Security considerations:** un cursor manipulado no puede saltar a datos de otro monitor, porque solo contiene una fecha y la consulta siempre filtra por el `monitorId` autorizado. `limit` acotado contra consultas caras (disponibilidad).
- **Dependencies:** OW-027.
- **Definition of Done:** tiempo de `stats?window=30d` con 86 400 filas anotado.
  - **Medido el 2026-10-05** (local, Windows con Docker Desktop, PostgreSQL 18.6 de Testcontainers, caché caliente, una sola tabla con ese monitor): `statsOf` completo, autorización incluida, 27 a 33 ms en 20 llamadas (mediana 31 ms); la consulta de totales y percentiles, 20,6 ms (`EXPLAIN ANALYZE`), y la de fallos por causa, 3,6 ms. Anotado en [retención](../database/data-retention.md#cálculo-del-uptime-en-v1).

### OW-029 · Job de retención
`feature` `performance` · P2 · Milestone: v0.3.0 — Motor de monitoreo · **Hecha**

- **Context:** crecimiento de `monitor_checks` ([retención](../database/data-retention.md)). Los refresh tokens ya los purga `RefreshTokenPurgeJob` (OW-014), y el archivo del registro de eventos `EventPublicationPurgeJob` (OW-034): esta issue no los toca.
- **Objective:** purga diaria en lotes de los checks antiguos, y de los checks y el estado de los monitores borrados.
- **Tasks:**
  - [x] `CheckRetentionJob` con `opswatch.retention.cron`, `opswatch.retention.checks` (30 días) y lotes de `opswatch.retention.batch-size` (10 000).
    - `CheckRetentionProperties` valida `checks` (positivo) y `batch-size` (al menos 1): una retención imposible no arranca la aplicación.
  - [x] **Sin advisory lock** (decisión de Ricardo del 2026-10-03, coherente con OW-014 y OW-034): cada lote elige sus filas con `FOR UPDATE SKIP LOCKED`. Dos instancias se reparten el trabajo sin repetirlo ni esperarse, y ninguna retiene una conexión durante toda la purga.
    - Cada lote es una transacción propia. Se repite hasta que uno vuelve con menos filas que el lote: lo que quedara lo tiene bloqueado otra instancia, que lo borra.
  - [x] Checks de los monitores borrados en lotes, y después su `monitor_state`. La fila de `monitors` se queda: la referenciarán los incidentes desde la v0.4.0.
    - Los checks de un monitor borrado se borran sea cual sea su edad. Su estado, solo cuando ya no le quedan checks (`NOT EXISTS`), con `FOR UPDATE OF s SKIP LOCKED`: un resultado que se está registrando retiene la fila y el monitor espera a la purga siguiente.
    - **Añadido durante la implementación:** si un resultado se registra justo entre la comprobación de que no quedan checks y el bloqueo del estado, ese check queda sin estado hasta la purga siguiente, que lo borra. No rompe nada: `monitor_checks` referencia a `monitors`, no a `monitor_state`.
  - [x] Si el estado de un monitor borrado ya no existe cuando vuelve un check en vuelo, el registro descarta el resultado sin error.
    - `CheckResultRecorder` no guarda el check y no lo cuenta, ni como resultado ni como `ERROR`. Antes lanzaba y contaba un `ERROR`.
  - [x] Métricas de filas borradas y de duración (`opswatch_retention_deleted_rows_total{table}` y `opswatch_retention_duration_seconds{table}`).
    - `table` es `monitor_checks` (los checks antiguos y los de monitores borrados) o `monitor_state`. Se exportan a Prometheus con OW-030.
- **Acceptance Criteria:**
  - Borra solo lo anterior a la fecha de corte.
  - Dos instancias a la vez no borran dos veces la misma fila ni se esperan.
  - Lotes de 10 000 filas como máximo por transacción.
  - El estado de un monitor borrado desaparece solo después de sus checks.
- **Testing:**
  - Integración: `CheckRetentionJobIT`, incluidas dos ejecuciones simultáneas.
    - Los checks del test son de 1995, un pasado que no usa ningún otro test: el corte cae entre ellos y después de nada más. El job se construye en el test con un `Clock` fijo.
    - Las dos ejecuciones: una retiene su lote sin confirmar y la otra borra el resto sin esperarla; entre las dos, cada fila una vez.
    - El registro de un resultado sin estado está en `CheckResultRecorderIT`, y la validación de las propiedades en `CheckRetentionPropertiesTest`.
  - Comprobado que los tests detectan el fallo: con el corte inclusivo, sin `SKIP LOCKED` o sin la condición de que no queden checks, fallan.
- **Security considerations:** integridad y disponibilidad: un error en la fecha de corte borraría historial válido (el test lo cubre), y un `DELETE` masivo sin lotes bloquearía la tabla. Sin dato sensible nuevo.
- **Dependencies:** OW-027.
- **Definition of Done:** propiedades de retención en el catálogo y en [retención](../database/data-retention.md).

### OW-030 · Métricas del motor con Micrometer
`devops` `performance` · P2 · Milestone: v0.3.0 — Motor de monitoreo · **Hecha**

- **Context:** sin métricas no hay evidencia para las decisiones de las Fases 7 a 10. El gauge `opswatch_event_publications_incomplete` (OW-034) ya existe y se exporta con lo demás.
- **Objective:** las métricas de la Fase 3 de [observabilidad](../devops/observability.md#métricas-propias) en `/actuator/prometheus`.
- **Tasks:**
  - [x] `io.micrometer:micrometer-registry-prometheus` sin versión propia: la fija el BOM (1.17.1). Decisión de Ricardo del 2026-10-03.
  - [x] `prometheus` en `management.endpoints.web.exposure.include`, solo en el puerto de management (8081), que no se publica.
  - [x] Contadores, histogramas con buckets explícitos y gauges recalculados de forma periódica, nunca en el scrape. Incluye `opswatch_egress_blocked_total{reason}`.
    - `opswatch_monitor_checks_total{outcome, reason}` ya existe (OW-027, en `CheckResultRecorder`); `reason` es `NONE` cuando no hay `FailureReason`.
    - Los nombres viven en `EngineMetrics`, y los buckets de los histogramas en `application.yml` (`management.metrics.distribution.slo`): lag y duración de los checks como en el documento de observabilidad; **añadidos** los del claim (5 ms a 1 s) y los de las purgas de retención (0,1 s a 15 min).
    - `opswatch_monitor_check_duration_seconds{outcome}` mide la petición; `outcome` es el del check o `ERROR` si la petición lanzó. Un check abandonado al apagar no se mide.
    - `opswatch_monitor_checks_in_flight` existe mientras el dispatcher corre: se registra al arrancar y se quita al parar.
    - `opswatch_monitor_checks_overdue` lo recalcula `OverdueChecks` cada 15 s, en todas las instancias, también sin motor. Llega `opswatch.monitoring.engine.overdue-threshold` (5 s).
    - `opswatch_scheduler_dispatcher_saturated_total` existe desde que arranca el dispatcher, a cero.
    - `opswatch_egress_blocked_total{reason}`: `ADDRESS`, `URL` o `HEADER`, lo que el cliente de `egress` para al salir. Los rechazos al guardar ya son un `422` y no cuentan.
  - [x] Test de que ninguna métrica lleva etiquetas de alta cardinalidad.
  - [x] Medición informal con 100 y 1 000 monitores contra un destino local.
  - **Añadido durante la implementación:** `ApplicationStartupIT` arrancaba sin el perfil `test`, así que desde OW-026 su contexto tenía el motor activo, con el DNS y el cliente reales, y mientras seguía en la caché de contextos comprobaba los monitores vencidos de la base compartida. Ahora lo desactiva por propiedad.
- **Acceptance Criteria:** después de ejecutar checks, las métricas aparecen con los valores esperados; ninguna etiqueta tiene `monitorId`, `organizationId` ni URL.
- **Testing:**
  - Integración: `EngineMetricsIT` y el test de cardinalidad.
    - `EngineMetricsIT`: lag, duración y claim tras checks reales con un cliente falso, con sus buckets; error de la petición; checks en vuelo y dispatches saturados; vencidos con un reloj de 2001 (`PastSchedule`); el scrape de Prometheus con los nombres del catálogo; y ninguna etiqueta con nombres de identificadores, UUID ni URL en **todas** las métricas de la aplicación.
    - `ApplicationStartupIT`: `/actuator/prometheus` responde en el puerto de management y no en el de la API.
    - `GuardedDnsResolverTest` y `EgressHttpClientsTest` cuentan los bloqueos por razón.
  - Rendimiento: la medición informal (no es un benchmark formal).
  - Comprobado que los tests detectan el fallo: con el id del monitor como etiqueta o sin los buckets, fallan.
- **Security considerations:** `/actuator/prometheus` solo en el puerto de management, que no se publica; sin identificadores de clientes en las métricas (fuga de información y cardinalidad).
- **Dependencies:** OW-026, OW-027.
- **Definition of Done:** resultado de la medición informal en `docs/performance/results/`.
  - [Medición del 2026-10-05](../performance/results/2026-10-05-medicion-informal-motor.md): con 1 000 monitores a 30 s, 33,1 checks/s de 33,3, lag p95 0,97 s, claim p95 17 ms, 6 % de un núcleo y sin duplicados.

### OW-031 · Tests de concurrencia del motor
`testing` · P1 · Milestone: v0.3.0 — Motor de monitoreo · **Cerrada**

Fusionada en OW-026 (claimers concurrentes) y OW-027 (pausa concurrente con el registro de un resultado): un test de concurrencia pertenece a la issue que introduce la concurrencia.

---

## v0.4.0 — Incidentes y notificaciones

Orden: OW-032 → OW-033 → OW-035 → OW-036 → OW-043. Las entregas (OW-036) necesitan incidentes que notificar (OW-032) y canales a los que enviar (OW-035). La firma de los webhooks (OW-043) va al final porque reutiliza el worker de OW-036.

Refinada el 2026-10-05 contra lo que dejó construido la v0.3.0:
- `MonitorWentDown` y `MonitorRecovered` se publican dentro de la transacción de `CheckResultRecorder`, que bloquea la fila de `monitor_state` y **nunca lanza**: si un listener síncrono falla, se revierten el check y su cambio de estado, y se cuenta como `ERROR`;
- `MonitorPaused` y `MonitorDeleted` se publican dentro de la transacción de la pausa y del borrado, también con la fila del estado bloqueada; la limpieza de un proyecto borra monitor a monitor (OW-044);
- la retención conserva la fila de `monitors` para que la referencien los incidentes (OW-029);
- en `egress`, `TargetKind.WEBHOOK` (solo `https`) y `EgressHttpClients`, cuyos clientes nunca siguen redirects;
- `SecretCipher` con dato asociado, el registro de eventos (OW-034), `PageQuery`, `ETags`, `ProjectDirectory.lockActive`, `LockSpace`, `AccessControl` con los permisos `INCIDENT_*` y `CHANNEL_*`, y la matriz de autorización;
- el limitador de peticiones vive en `identity.security` (`AuthRateLimiter`), donde `notification` no puede usarlo.

Decisiones de Ricardo en el refinamiento (2026-10-05):
- acknowledge y recuperación se serializan con un bloqueo pesimista de la fila del incidente, no con `@Version`: así ningún check se pierde;
- emails multipart, en texto y en HTML, con Thymeleaf;
- GreenMail como SMTP de los tests de integración;
- los webhooks no siguen redirects;
- los canales limitados a un proyecto se borran con él;
- los incidentes de un proyecto o monitor borrado siguen visibles como historial;
- versiones: `spring-boot-starter-mail` y Thymeleaf sin versión propia (las fija el BOM de Boot 4.1.1: Angus Mail 2.0.5 y Thymeleaf 3.1.5), `com.icegreen:greenmail-junit5` 2.1.14 en test y `axllent/mailpit:v1.31.4` fijado por digest;
- la versión en `/actuator/info` (`build-info`) pasa a la Fase 6, donde la comprueban los smoke tests.

### OW-032 · Incidentes: apertura y resolución automáticas
`feature` · P1 · Milestone: v0.4.0 — Incidentes y notificaciones · **Hecha**

- **Context:** [ciclo de vida de incidentes](../architecture/incident-lifecycle.md) y [eventos](../architecture/events.md). Los cuatro eventos del monitor ya se publican (OW-027 y OW-044), dentro de transacciones que tienen bloqueada la fila de `monitor_state`, y nadie los escucha todavía.
- **Objective:** el listener síncrono de `incident` abre y resuelve incidentes a partir de los eventos del monitor.
- **Tasks:**
  - [x] Migración `incident_create_incidents_and_timeline`, con el DDL del [diseño](../database/database-design.md) y el índice único parcial `ux_incidents_one_active_per_monitor` (`(monitor_id) WHERE status <> 'RESOLVED'`).
  - [x] `MonitorEventsListener` (`@EventListener`, en la transacción del publicador) para `MonitorWentDown`, `MonitorRecovered`, `MonitorPaused` y `MonitorDeleted`. Sin I/O externo: si lanza, `CheckResultRecorder` revierte el check entero, y la pausa o el borrado fallan.
  - [x] Apertura idempotente con `INSERT … ON CONFLICT DO NOTHING` sobre el índice parcial, con el nombre del monitor, la causa y el código HTTP del evento, y su entrada `OPENED` en el timeline. `opened_at` es el `occurredAt` del evento.
  - [x] Resolución: lee el incidente activo con `FOR UPDATE`, en el orden de bloqueos estado del monitor → incidente (el acknowledge de OW-033 toma el mismo bloqueo), y lo pasa a `RESOLVED` con `AUTO_RECOVERED`, `MONITOR_PAUSED` o `MONITOR_DELETED`. `resolved_by` es quien pausó o borró, y nulo en la recuperación. Añade su entrada `RESOLVED`. Sin incidente activo no hace nada (pausar un monitor `UP`, por ejemplo).
  - [x] Eventos `IncidentOpened`, `IncidentAcknowledged` e `IncidentResolved` en el paquete raíz de `incident`, con la forma del [catálogo](../architecture/events.md#incident). Se publican en la misma transacción; los escuchará `notification` con el registro (OW-036).
  - [x] Métricas `opswatch_incidents_opened_total` y `opswatch_incidents_active`. El gauge lo recalcula una tarea programada cada 30 s, nunca el scrape.
  - **Decisiones de la implementación:**
    - la migración es `V9`. Añade dos restricciones que el diseño no tenía: `ck_incidents_cause`, con las mismas causas que `monitor_checks`, y `ck_incidents_monitor_name`, con el límite de 100 caracteres del nombre de un monitor;
    - `IncidentLifecycle` lleva `@Transactional(propagation = MANDATORY)`: un evento publicado fuera de una transacción falla en lugar de escribir por su cuenta, porque así no se podría mantener la invariante;
    - la apertura va con JDBC (`NewIncidentRepository`) y la resolución con JPA (`IncidentRepository.findActiveByMonitorIdForUpdate`); el timeline, también con JDBC, porque solo se añade;
    - el contador de aperturas y el log `incident.opened`/`incident.resolved` van después del commit: un check revertido no abrió nada;
    - `Resolution` va en el paquete raíz, porque viaja en `IncidentResolved`; la causa viaja como texto, para que `notification` no dependa de `monitoring`.
- **Acceptance Criteria:** una caída = un incidente; pausar un monitor caído lo resuelve con `MONITOR_PAUSED`; borrar el proyecto de un monitor caído lo resuelve con `MONITOR_DELETED`; dos `MonitorWentDown` seguidos no crean dos incidentes; insertar a mano un segundo incidente activo para el mismo monitor viola `ux_incidents_one_active_per_monitor`; si el listener falla, el check no se guarda, el estado no se mueve y el siguiente check vuelve a evaluar la transición.
- **Testing:**
  - Módulo: `IncidentModuleIT` (`@ApplicationModuleTest` con `Scenario`): apertura, evento repetido, recuperación, pausa y borrado con su autor, pausa sin incidente, una caída nueva tras resolver y un evento fuera de transacción.
  - Integración: la restricción única y la de las resoluciones completas (`IncidentRepositoryIT`).
  - Integración: `MonitorIncidentsIT` con el `CheckResultRecorder`, el `MonitorService` y el `ProjectService` reales: una caída y su recuperación, la pausa y la reanudación, el borrado del proyecto, un check revertido por un listener que lanza (sin incidente ni contador) y el gauge.
  - Comprobado que los tests detectan el fallo: sin `ON CONFLICT` el evento repetido lanza, y con el contador antes del commit cuenta una apertura revertida.
- **Security considerations:** integridad del estado entre módulos: el listener síncrono y el índice único garantizan la invariante aunque haya duplicados. Los eventos llevan solo ids, el nombre del monitor y la causa, nunca la URL ni los headers.
- **Dependencies:** OW-027, OW-044.
- **Definition of Done:** la invariante "un incidente activo por monitor" está probada en la base de datos y en la aplicación.

### OW-033 · Acknowledge, listados y timeline
`feature` · P2 · Milestone: v0.4.0 — Incidentes y notificaciones · **Hecha**

- **Context:** la interacción humana con los incidentes. No hay resolución manual en V1 ([por qué](../architecture/incident-lifecycle.md#por-qué-no-hay-resolución-manual-r6)).
- **Objective:** `GET /api/v1/organizations/{orgId}/incidents`, `GET /api/v1/incidents/{incidentId}` y `POST /api/v1/incidents/{incidentId}/acknowledge`.
- **Tasks:**
  - [x] Acknowledge con nota opcional (500 caracteres como máximo). Lee el incidente con `FOR UPDATE`, el mismo bloqueo que la resolución de OW-032 (decisión de Ricardo del 2026-10-05): `OPEN` pasa a `ACKNOWLEDGED`, con su entrada en el timeline y `IncidentAcknowledged`. Cualquier otro estado da `409 business-rule-violation`.
  - [x] Listado por organización con `PageQuery`: filtros `status`, `projectId`, `monitorId`, `from` y `to`; `sort` por `openedAt` (por defecto, descendente) o `resolvedAt`.
  - [x] Detalle con el timeline, el actor de cada entrada (`UserDirectory`) y `durationSeconds` al resolverse.
  - [x] Autorización por la organización del incidente (`AccessControl.require` con `INCIDENT_READ` o `INCIDENT_ACKNOWLEDGE`), no por su proyecto: los incidentes de un proyecto o un monitor borrados siguen visibles como historial (decisión de Ricardo del 2026-10-05). Quien no es miembro recibe un `404` que habla del incidente.
  - [x] Filas nuevas en la matriz de autorización.
  - **Decisiones de la implementación:**
    - `incident` puede depender de `identity` (solo `UserDirectory`) para los nombres del timeline: decisión de Ricardo del 2026-10-05. La regla de módulos lo prohibía y el refinamiento no lo vio. Pasar por `organization` dejaría sin nombre a quien ya no es miembro;
    - la nota es una línea (`VisibleText`): sin saltos de línea ni caracteres de control. Un cuerpo vacío o sin nota es válido;
    - el listado va con una `Specification` de Spring Data (`IncidentFilter`), y `status` admite varios valores, como el historial de checks;
    - el acknowledge vacía el contexto de persistencia (`flush`) antes de responder, para devolver la versión nueva;
    - el listado no lleva el timeline ni `acknowledgedBy`: el detalle sí.
- **Acceptance Criteria:** acknowledge sobre `OPEN` → `ACKNOWLEDGED`; sobre `RESOLVED` → `409`; un `VIEWER` → `403`; acknowledge a la vez que la recuperación: las dos se serializan sobre la fila del incidente; si gana la recuperación, el acknowledge da `409 business-rule-violation`, y el check se guarda siempre; los incidentes de un proyecto borrado salen en el listado y por id.
- **Testing:**
  - API y seguridad: `IncidentApiIT` (acknowledge con y sin nota, `409` sobre un incidente ya reconocido o resuelto, notas rechazadas, detalle de un incidente resuelto por una pausa, listado con filtros y orden, parámetros rechazados, incidentes de un proyecto borrado, IDOR y OpenAPI) y tres filas nuevas en `EndpointAuthorizationMatrixIT`.
  - Integración (concurrencia): `AcknowledgeConcurrencyIT`. Con la fila del incidente retenida por una recuperación, el acknowledge espera y después da `409`; con el acknowledge primero, el check que recupera el monitor espera y resuelve el incidente `ACKNOWLEDGED`, sin perder el check.
  - Comprobado que los tests detectan el fallo: sin el `FOR UPDATE` del acknowledge, falla por versión en lugar de dar `409`.
- **Security considerations:** la nota tiene como máximo 500 caracteres y se escapa al mostrarla (XSS en un frontend futuro). El bloqueo de la fila evita perder actualizaciones sin descartar checks.
- **Dependencies:** OW-032.
- **Definition of Done:** el catálogo de endpoints coincide con la implementación.

### OW-035 · Canales de notificación (email y webhook)
`feature` `security` · P2 · Milestone: v0.4.0 — Incidentes y notificaciones · **Ready**

- **Context:** a dónde avisar ([modelo de dominio](../architecture/domain-model.md#notificationchannel)). El endpoint de prueba y Mailpit pasan a OW-036: hasta entonces no hay nada que envíe.
- **Objective:** CRUD de canales con la configuración cifrada y un secreto de firma que se muestra una sola vez.
- **Tasks:**
  - [ ] Migración `notification_create_channels_and_deliveries`. `notification_deliveries` admite ya las entregas de prueba de OW-036: `event_type` `TEST` con `incident_id` nulo.
  - [ ] Crear, listar, leer, `PATCH`, borrar y `rotate-secret`, como en el [catálogo](../api/endpoints-v1.md#canales-de-notificación-notification), con `ETag` e `If-Match` como las organizaciones.
  - [ ] Configuración cifrada con `SecretCipher`. El dato asociado lleva el propósito y el id del canal, distinto del de los headers de monitor, para que un texto cifrado no se pueda mover de una tabla a otra.
  - [ ] Email: de 1 a `recipients-per-channel` (10) direcciones válidas y sin repetir.
  - [ ] Webhook:
    - la URL pasa `TargetPolicy.validate(url, TargetKind.WEBHOOK)`, que solo admite `https` y resuelve el DNS fuera de la transacción;
    - secreto de 32 bytes de `SecureRandom` con prefijo `whsec_`, que solo sale en las respuestas de creación y de rotación. En el resto, la configuración va enmascarada.
  - [ ] Cuota `channels-per-organization` (10), serializada con un advisory lock como la de monitores (espacio nuevo en `LockSpace`).
  - [ ] `projectId` opcional: el proyecto tiene que ser de la organización y no estar borrado. El canal se crea con el proyecto en `FOR SHARE` (`ProjectDirectory.lockActive`), como un monitor, para que no sobreviva a un borrado concurrente.
  - [ ] Listener asíncrono de `ProjectDeleted` (`@ApplicationModuleListener`, registro de OW-034) que borra los canales del proyecto y, por cascada, sus entregas (decisión de Ricardo del 2026-10-05). Idempotente.
  - [ ] Filas nuevas en la matriz de autorización.
- **Acceptance Criteria:** un webhook `http://` → `422`; el secreto solo aparece en la respuesta de creación y en la de rotación; el canal 11 de una organización → `422 quota-exceeded`; un `projectId` de otra organización → `404`; borrar el proyecto borra sus canales.
- **Testing:**
  - API y seguridad: endpoints por rol, IDOR y enmascarado de la configuración.
  - Seguridad: caso 26 de la tabla de SSRF (webhook `http`), URL de webhook hacia una red privada y un texto cifrado de otro canal que no se descifra.
  - Integración: borrado de los canales por `ProjectDeleted`.
- **Security considerations:** T-30 (SSRF por webhooks), T-32 (lectura de secretos) y T-35 (límites de canales y destinatarios).
- **Dependencies:** OW-018, OW-022, OW-024, OW-044.
- **Definition of Done:** el catálogo de endpoints coincide con la implementación.

### OW-036 · Entrega de notificaciones: listener, worker con reintentos y email
`feature` · P2 · Milestone: v0.4.0 — Incidentes y notificaciones · **Ready**

- **Context:** efecto lateral fiable a partir de los eventos de incidentes. Los webhooks firmados van en OW-043.
- **Objective:** listener asíncrono que crea las entregas, worker que las envía por email con backoff, y endpoint de prueba de canales.
- **Tasks:**
  - [ ] Dependencias `spring-boot-starter-mail` y `org.thymeleaf:thymeleaf` sin versión propia (el BOM fija Angus Mail 2.0.5 y Thymeleaf 3.1.5), y `com.icegreen:greenmail-junit5` 2.1.14 en test. Decisión de Ricardo del 2026-10-05.
  - [ ] `IncidentEventsListener` (`@ApplicationModuleListener`, registro de OW-034): una entrega `PENDING` por canal habilitado aplicable, es decir, de la organización y del proyecto del incidente o sin proyecto. La clave única da la idempotencia (`ON CONFLICT DO NOTHING`).
  - [ ] `DeliveryWorker`, en todas las instancias, sin I/O dentro de transacciones:
    - reclama en una transacción corta con `FOR UPDATE SKIP LOCKED`, suma el intento y aparta la entrega (`next_attempt_at` = ahora + timeout del envío + margen) para que otra instancia no la tome en vuelo;
    - envía fuera de la transacción;
    - guarda el resultado en otra transacción corta: `SENT`, el siguiente intento según el backoff (0 s, 30 s, 2 min, 10 min, 30 min y 1 h) o `FAILED` tras el sexto;
    - la entrega es at-least-once: una caída entre el envío y el registro repite el envío;
    - un canal deshabilitado no envía: su entrega pasa a `FAILED` con `channel disabled`.
  - [ ] `EmailSender` con Spring Mail:
    - multipart en texto y en HTML con Thymeleaf (decisión de Ricardo), con un `TemplateEngine` propio para las plantillas de email, sin el starter ni `ViewResolver`;
    - `th:text` escapa el nombre del monitor, y el asunto no admite saltos de línea;
    - remitente `opswatch.notification.email.from`, obligatorio;
    - timeouts de SMTP (`mail.smtp.connectiontimeout`, `timeout` y `writetimeout`), porque Jakarta Mail espera sin límite por defecto;
    - `management.health.mail.enabled=false`, para que un SMTP caído no tumbe la readiness.
  - [ ] `POST /api/v1/notification-channels/{channelId}/test` (desde OW-035): `202` y una entrega `TEST`, sin incidente, que procesa el mismo worker. 5 por minuto por canal (`opswatch.notification.test.rate-limit`).
  - [ ] Limitador genérico en `shared`, sacado de `AuthRateLimiter` sin cambiar su comportamiento: `notification` no puede depender de `identity`.
  - [ ] `GET /api/v1/notification-channels/{channelId}/deliveries`, paginado, con el estado y los intentos, nunca el contenido.
  - [ ] Purga diaria de las entregas de más de `opswatch.retention.deliveries` (90 días), con el cron común y en lotes con `SKIP LOCKED`, como OW-029. Cuenta en `opswatch_retention_deleted_rows_total{table="notification_deliveries"}`.
  - [ ] Métrica `opswatch_notification_deliveries_total{channel_type, result}`.
  - [ ] Mailpit en Compose (profile `mail`), `axllent/mailpit:v1.31.4` fijado por digest (desde OW-035).
- **Acceptance Criteria:**
  - Una caída de 10 minutos → exactamente un email de apertura y uno de resolución por canal.
  - Con el SMTP caído, las entregas se reintentan y acaban en `SENT` al volver, o en `FAILED` tras 6 intentos.
  - Un `IncidentOpened` duplicado no crea una segunda entrega.
  - Si la aplicación se reinicia entre la apertura del incidente y la creación de las entregas, se crean al reiniciar, una sola vez.
  - El sexto envío de prueba de un canal en un minuto → `429`.
  - Con el SMTP caído, la readiness sigue `UP`.
- **Testing:**
  - Integración: worker contra GreenMail, parándolo dentro del test para simular la caída (reintentos con `MutableClock`, `SENT` al volver y `FAILED` tras 6 intentos).
  - Módulo: idempotencia ante eventos duplicados.
  - Integración: reinicio con publicaciones pendientes, como `ProjectCleanupRestartIT`.
  - Seguridad: un nombre de monitor con HTML y con saltos de línea sale escapado en el cuerpo y no rompe el asunto.
- **Security considerations:** T-33 (un proveedor lento no bloquea las entregas: timeouts y worker aparte), T-34 (inyección en las plantillas: escapado) y T-35 (rate limit del endpoint de prueba). Ningún I/O externo dentro de transacciones de negocio.
- **Dependencies:** OW-032, OW-034, OW-035.
- **Definition of Done:** el flujo de eventos de `events.md` coincide con la implementación.

### OW-043 · Webhooks firmados con HMAC
`feature` `security` · P2 · Milestone: v0.4.0 — Incidentes y notificaciones · **Ready**

- **Context:** separado de OW-036 para que la entrega por webhook y su firma tengan su propia revisión de seguridad.
- **Objective:** `WebhookSender` a través de `egress`, con cuerpo generado por OpsWatch y firma verificable.
- **Tasks:**
  - [ ] `WebhookSender`: `POST` JSON por un cliente de `EgressHttpClients` con `TargetKind.WEBHOOK` y un deadline de 5 s sobre el envío entero (`opswatch.notification.webhook.timeout`), como el del motor.
  - [ ] **Sin redirects** (decisión de Ricardo del 2026-10-05): un `3xx` es un intento fallido, igual que cualquier respuesta fuera de `2xx`. El cuerpo firmado nunca sale hacia otra URL.
  - [ ] Cuerpo del [catálogo](../api/endpoints-v1.md#canales-de-notificación-notification), con `id` = id de la entrega para que el receptor descarte los duplicados de la entrega at-least-once, y `type` `TEST` en las pruebas.
  - [ ] `X-OpsWatch-Signature: t=<timestamp>,v1=<HMAC-SHA256>` y `X-OpsWatch-Webhook-Version: 1`.
  - [ ] Guía breve para los receptores: cómo verificar la firma y rechazar marcas de tiempo antiguas.
- **Acceptance Criteria:** un verificador independiente escrito en el test valida la firma con el secreto del canal; un `3xx`, también hacia `http://` o hacia una IP privada, cuenta como intento fallido y su destino no recibe nada; una URL que ya resuelve a una IP privada falla sin enviar el cuerpo; un receptor que tarda más de 5 s cuenta como intento fallido.
- **Testing:**
  - Integración: `WebhookSenderIT` contra WireMock.
  - Seguridad: firma, redirects y SSRF por DNS (`FakeHostResolver`).
- **Security considerations:** T-30 (SSRF), T-31 (suplantación de OpsWatch ante el receptor). La marca de tiempo en la firma permite al receptor rechazar repeticiones.
- **Dependencies:** OW-024, OW-035, OW-036.
- **Definition of Done:** la guía de verificación está enlazada desde el catálogo de endpoints.

---

## v0.5.0 — Endurecimiento de seguridad (por detallar)

Estas issues existen para que el alcance de la v0.5.0 sea visible, pero **no están listas**: se detallan con el formato completo al cerrar la v0.4.0.

| ID | Título | Tipos | Prioridad | Estado |
|---|---|---|---|---|
| OW-037 | Verificación de email y reset de contraseña; registro sin enumeración | `feature` `security` | P2 | Por detallar |
| OW-038 | Invitaciones a organizaciones (sustituyen al alta directa por email) | `feature` `security` | P2 | Por detallar |
| OW-039 | Rechazo de contraseñas comunes (lista local) | `security` | P3 | Por detallar |
| OW-040 | Audit log de acciones sensibles | `feature` `security` | P2 | Por detallar |
| OW-041 | Rate limiting general de la API y límite de concurrencia por host de destino | `security` `performance` | P2 | Por detallar |
| OW-042 | CodeQL, escaneo ZAP baseline, comparación de OpenAPI en CI y revisión completa del threat model | `security` `devops` `documentation` | P2 | Por detallar |

## v1.0.0 — V1 desplegada

Sin issues todavía: se crean al cerrar la v0.5.0 a partir de la [Fase 6 del roadmap](roadmap.md#fase-6-despliegue-y-cd--100-v1). Crearlas ahora sería planificar un despliegue que depende de decisiones abiertas (hosting, proveedor de email).
