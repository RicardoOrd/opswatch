# Backlog

Estado: sincronizado con GitHub Issues · Última revisión: 2026-09-28

Este fichero es la **fuente única** de las issues. Cada issue de GitHub se genera a partir de su entrada aquí: si una issue cambia, se cambia aquí y se vuelve a sincronizar con [`scripts/sync-issues.mjs`](../../scripts/sync-issues.mjs) (Node 22 y `gh` autenticado):

```bash
node scripts/sync-issues.mjs         # simulación: muestra qué cambiaría
node scripts/sync-issues.mjs --run   # aplica los cambios
```

El script crea las issues que faltan, actualiza título, cuerpo, etiquetas de tipo y prioridad y milestone, y cierra las entradas marcadas como **Cerrada**. Es idempotente: si GitHub ya coincide, no hace nada. No toca las etiquetas que no gestiona (por ejemplo `bug`). De OW-001 a OW-044, número de issue = número OW + 1 (OW-001 es la #2, porque la #1 es el PR de documentación). Las issues futuras (releases, Fase 6 en adelante) no siguen esa regla: se enlazan por su número real.

**Project de GitHub:** [OpsWatch](https://github.com/users/RicardoOrd/projects/3), público y enlazado al repositorio. Tiene un solo campo propio, `Status`: Backlog, Ready, In Progress, Review y Done. La prioridad y el tipo van en etiquetas y la fase en el milestone, que el Project muestra como campos nativos. `Status` no lo gestiona `sync-issues.mjs`: se mueve a mano al empezar una issue. Los workflows del Project (**Item closed** → Done, **Pull request merged** → Done, **Item added** → Backlog y **Auto-add** para las issues nuevas del repositorio) se activan desde la configuración del Project, porque la API de GitHub no permite activarlos.

**Foco actual: Sprint 0** (OW-001 a OW-010). Todo lo demás está planificado, pero no listo para empezar.

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

### Etiquetas

- **Tipo:** `feature`, `architecture`, `security`, `testing`, `devops`, `documentation`, `performance`, `refactor`, `bug`. Una issue puede tener más de un tipo.
- **Prioridad:** `P0`, `P1`, `P2`, `P3`. Exactamente una por issue.
- No hay etiquetas de fase: la fase la indica el milestone.

### Milestones

Cada fase del roadmap es un milestone. Cerrar un milestone de versión = publicar su release con el [proceso de release](../development/versioning.md#proceso-de-release). **Ninguna issue funcional exige crear un tag.**

| Milestone | Issues | Resultado demostrable |
|---|---|---|
| Sprint 0 — Fundaciones | OW-001 a OW-010 | Esqueleto que arranca con PostgreSQL, rechaza todo con `401` en formato Problem Details y tiene CI en verde |
| v0.1.0 — Identity y organizaciones | OW-012 a OW-018 | Registro, login, organizaciones y roles, con el aislamiento entre organizaciones probado |
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
`feature` `security` · P2 · Milestone: v0.1.0 — Identity y organizaciones · **Planned**

- **Context:** primer caso de uso real. Fija el patrón que seguirán los demás: DTO, servicio, dominio, migración y tests de API.
- **Objective:** `POST /api/v1/auth/register` según el [catálogo](../api/endpoints-v1.md#autenticación-identity).
- **Tasks:**
  - [ ] Migración `identity_create_users` (el número de versión se asigna al implementarla).
  - [ ] Entidad `User` con sus invariantes (email normalizado, longitud del nombre).
  - [ ] `RegistrationService` con `DelegatingPasswordEncoder` (bcrypt, coste 12).
  - [ ] Validación de la contraseña: de 12 caracteres a 72 bytes UTF-8.
  - [ ] Generador de UUIDv7.
  - [ ] Primer builder de test (`aUser()`), según la [convención](../testing/testing-strategy.md#convenciones); aplazado desde OW-007, que no tenía entidades.
- **Acceptance Criteria:** `201` con el usuario sin hash; un email duplicado, sin distinguir mayúsculas, da `409`; una contraseña de 73 bytes da `400`; el hash guardado empieza por `{bcrypt}`.
- **Testing:**
  - Unitarios: invariantes de `User`, validación de la contraseña (límites de 12 caracteres y 72 bytes con caracteres multibyte).
  - Integración: `UserRepositoryIT` (índice único de email).
  - API: `RegistrationApiIT` (`201`, `400` con `errors[]`, `409`, propiedad desconocida → `400`).
- **Security considerations:** el hash nunca sale en respuestas ni en logs; las propiedades desconocidas se rechazan (mass assignment). Enumeración de emails aceptada hasta la Fase 5 (T-06), mitigada por el rate limit de OW-015.
- **Dependencies:** Sprint 0.
- **Definition of Done:** endpoint documentado en OpenAPI con sus errores.

### OW-013 · Login con access token JWT
`feature` `security` · P1 · Milestone: v0.1.0 — Identity y organizaciones · **Planned**

- **Context:** [ADR-004](../adr/ADR-004-security-strategy.md): JWT RS256 de 15 minutos validado por el Resource Server de Spring Security.
- **Objective:** `POST /api/v1/auth/login` que emite el access token, y validación del JWT en toda la API.
- **Tasks:**
  - [ ] Dependencia de OAuth2 Resource Server.
  - [ ] `JwtEncoder` y `JwtDecoder` con claves desde la configuración (`kid`, `iss`, `aud`, algoritmo fijado).
  - [ ] `AuthenticationService` con hash señuelo para emails inexistentes.
  - [ ] `CurrentUser` en `shared.security`.
  - [ ] `GET /api/v1/me`.
- **Acceptance Criteria:** un login correcto da un token que sirve en `GET /api/v1/me`; unas credenciales erróneas dan `401 invalid-credentials`, con el mismo cuerpo exista o no el email y una diferencia de tiempo mediana inferior a 50 ms entre los dos casos.
- **Testing:**
  - Seguridad: `JwtSecurityIT` con firma alterada, `alg: none`, HS256 firmado con la clave pública, token caducado, `iss` o `aud` incorrectos (todos → `401`) y usuario deshabilitado.
  - API: login correcto e incorrecto.
- **Security considerations:** algoritmo fijado contra la confusión de algoritmos (T-04); el token no lleva roles, así que la autorización nunca se basa en datos del token; la clave privada solo se lee de un secreto.
- **Dependencies:** OW-012.
- **Definition of Done:** el flujo de autenticación de la arquitectura de seguridad coincide con lo implementado.

### OW-014 · Refresh token con rotación, detección de reutilización y logout
`feature` `security` · P1 · Milestone: v0.1.0 — Identity y organizaciones · **Planned**

- **Context:** refresh token opaco en una cookie `HttpOnly`, que rota en cada uso y detecta la reutilización.
- **Objective:** `POST /api/v1/auth/refresh` y `POST /api/v1/auth/logout`; `POST /api/v1/me/password` revoca todas las familias.
- **Tasks:**
  - [ ] Migración `identity_create_refresh_tokens`.
  - [ ] `RefreshTokenService`: emitir, rotar con `SELECT … FOR UPDATE`, detectar la reutilización, revocar la familia.
  - [ ] Cookie con `HttpOnly`, `Secure`, `SameSite=Strict` y `Path=/api/v1/auth`.
  - [ ] Comprobación del header `Origin` en refresh y logout.
  - [ ] Job de purga de tokens vencidos.
- **Acceptance Criteria:**
  - Refresh → token nuevo, y el anterior deja de servir.
  - Reutilizar el anterior → `401` y la familia entera revocada.
  - Logout → cookie borrada y familia revocada.
  - Dos refresh concurrentes con el mismo token (50 repeticiones): exactamente uno recibe `200`, el otro `401`, y la familia queda revocada en todos los casos. Es el comportamiento estricto elegido: dos pestañas que refrescan a la vez cierran la sesión (compromiso documentado en ADR-004).
- **Testing:**
  - Unitarios: lógica de rotación y de familias.
  - Integración (concurrencia): `RefreshTokenIT` con PostgreSQL real.
  - Seguridad: atributos de la cookie, `Origin` ajeno → `403`, el token en claro nunca se guarda.
- **Security considerations:** solo se guarda el SHA-256 del token; la reutilización genera un evento de seguridad; `FOR UPDATE` evita que una carrera emita dos tokens válidos de la misma familia (T-03, T-09).
- **Dependencies:** OW-013.
- **Definition of Done:** cubiertos T-03 y T-09 del threat model.

### OW-015 · Rate limiting de los endpoints de autenticación
`security` · P1 · Milestone: v0.1.0 — Identity y organizaciones · **Planned**

- **Context:** protección contra fuerza bruta y credential stuffing, sin bloquear cuentas.
- **Objective:** los límites de la [tabla de rate limiting](../security/security-architecture.md#8-rate-limiting) con Bucket4j en memoria.
- **Tasks:**
  - [ ] Dependencia de Bucket4j.
  - [ ] `AuthRateLimiter` con claves por IP y por email.
  - [ ] `429` con `Retry-After` y Problem Details.
  - [ ] IP real detrás del proxy de confianza (`forward-headers-strategy`).
- **Acceptance Criteria:** el undécimo login por minuto desde una IP → `429`; el sexto intento contra un mismo email desde IPs distintas → `429`; un `X-Forwarded-For` enviado por un cliente directo no cambia la IP usada.
- **Testing:**
  - Integración: `AuthRateLimitIT` con `Clock` controlado.
  - Seguridad: suplantación de IP con `X-Forwarded-For`.
- **Security considerations:** sin bloqueo de cuentas, para que un atacante no pueda dejar fuera a la víctima; `X-Forwarded-For` solo desde proxies de confianza. Límite conocido: con varias instancias, los límites en memoria se multiplican (ADR-009).
- **Dependencies:** OW-013.
- **Definition of Done:** límites en el catálogo de propiedades.

### OW-016 · Organizaciones y `AccessControl`
`feature` `security` · P1 · Milestone: v0.1.0 — Identity y organizaciones · **Planned**

- **Context:** la organización es el tenant raíz y quien la crea queda como `OWNER`. Sus propios endpoints ya necesitan comprobar la membresía, así que `AccessControl` nace aquí.
- **Objective:** endpoints de organizaciones del catálogo y la API pública `AccessControl` del módulo `organization`.
- **Tasks:**
  - [ ] Migración `organization_create_organizations_and_memberships`.
  - [ ] Entidades `Organization` y `Membership`.
  - [ ] `Role` → `Permission` como mapa inmutable, según la [matriz](../security/authorization-model.md#matriz-rbac).
  - [ ] `AccessControl.require` y `requireForProject`: `404` a quien no es miembro, `403` al miembro sin permiso.
  - [ ] `OrganizationService`: creación con membresía `OWNER` en la misma transacción, cuota por usuario, borrado lógico y evento `OrganizationDeleted`.
  - [ ] `ETag` e `If-Match` en `PATCH`.
- **Acceptance Criteria:** una organización nueva aparece en `GET /api/v1/organizations` con `myRole: OWNER`; un no miembro recibe `404` en `GET /api/v1/organizations/{orgId}`; un `VIEWER` recibe `403` en `PATCH`; la sexta organización de un usuario → `422 quota-exceeded`; un `If-Match` obsoleto → `412`.
- **Testing:**
  - Unitarios: mapa `Role` → `Permission` contra una tabla de datos.
  - API: endpoints por rol y `412`.
  - Seguridad: IDOR (usuario de la organización B contra la A → `404` sin efectos).
- **Security considerations:** es el control del que dependen T-10 y T-11. La organización se obtiene del recurso, nunca de datos del cliente; los listados solo devuelven las organizaciones de las que el usuario es miembro.
- **Dependencies:** OW-013.
- **Definition of Done:** eventos documentados en `events.md`; la matriz del modelo de autorización coincide con el código.

### OW-017 · Miembros y roles con la invariante del último `OWNER`
`feature` `security` · P1 · Milestone: v0.1.0 — Identity y organizaciones · **Planned**

- **Context:** las reglas de gestión de roles del [modelo de autorización](../security/authorization-model.md#reglas-que-la-matriz-no-expresa).
- **Objective:** endpoints de miembros con todas las reglas.
- **Tasks:**
  - [ ] `MembershipService` con `SELECT … FOR UPDATE` sobre la organización.
  - [ ] Reglas `ADMIN` frente a `OWNER`, sin autopromoción, abandonar la organización.
  - [ ] `UserDirectory` (API pública de `identity`) para buscar usuarios por email.
  - [ ] Cuota de miembros.
- **Acceptance Criteria:**
  - Un `ADMIN` no puede asignar `ADMIN` → `403`.
  - El último `OWNER` no puede abandonar → `409`.
  - Dos `OWNER` que se degradan el uno al otro a la vez (50 repeticiones): siempre queda al menos un `OWNER` y una de las dos peticiones recibe `409`.
- **Testing:**
  - Unitarios: reglas de cambio de rol.
  - Integración (concurrencia): la carrera de los dos `OWNER` contra PostgreSQL real.
  - API y seguridad: endpoints por rol e IDOR.
- **Security considerations:** escalada de privilegios dentro de la organización (T-11) y carrera que deja una organización sin dueño (T-16). La enumeración de emails al añadir miembros es un riesgo aceptado hasta las invitaciones de OW-038 (T-06).
- **Dependencies:** OW-016.
- **Definition of Done:** las reglas de la sección 3 del modelo de autorización coinciden con el código.

### OW-018 · Matriz de autorización probada
`security` `testing` · P1 · Milestone: v0.1.0 — Identity y organizaciones · **Planned**

- **Context:** la autorización por recurso es la defensa principal contra el IDOR. Tiene que ser imposible añadir un endpoint sin probar su autorización.
- **Objective:** la tabla endpoint × rol como test, con un test de completitud que la mantiene al día.
- **Tasks:**
  - [ ] Tabla de datos endpoint × {`OWNER`, `ADMIN`, `MEMBER`, `VIEWER`, no miembro, anónimo} → código esperado.
  - [ ] Test parametrizado que recorre la tabla contra la aplicación real.
  - [ ] Test de completitud: todo endpoint registrado en Spring MVC bajo `/api/v1` tiene fila en la tabla.
- **Acceptance Criteria:** los endpoints de la v0.1.0 están en la tabla; añadir un endpoint sin fila hace fallar el build; cambiar un permiso sin actualizar la tabla hace fallar el build.
- **Testing:**
  - Seguridad: `EndpointAuthorizationMatrixIT` y el test de completitud.
- **Security considerations:** convierte T-10 y T-11 en una comprobación automática en cada PR, en lugar de depender de la revisión.
- **Dependencies:** OW-016, OW-017.
- **Definition of Done:** la [estrategia de testing](../testing/testing-strategy.md#pruebas-de-seguridad) describe cómo añadir filas.

---

## v0.2.0 — Proyectos y monitores

### OW-034 · Event Publication Registry de Spring Modulith
`architecture` · P2 · Milestone: v0.2.0 — Proyectos y monitores · **Planned**

- **Context:** el primer listener asíncrono entre módulos (`ProjectDeleted` → `monitoring`, en OW-044) necesita un registro persistente para no perder eventos ([eventos](../architecture/events.md)). Antes estaba en la Fase 4, pero OW-044 lo necesita en la v0.2.0.
- **Objective:** registro activo, con reenvío al reiniciar y limpieza de las publicaciones completadas.
- **Tasks:**
  - [ ] Dependencia del registro de Spring Modulith (JPA o JDBC).
  - [ ] Migración `modulith_create_event_publication` con el DDL de la versión fijada.
  - [ ] `republish-outstanding-events-on-restart`, modo de finalización y purga de las completadas.
  - [ ] Métrica `opswatch_event_publications_incomplete`.
- **Acceptance Criteria:** si el contexto se mata después del commit y antes de que se ejecute el listener, al reiniciar el listener se ejecuta exactamente una vez.
- **Testing:**
  - Integración: `EventPublicationRegistryIT` con reinicio del contexto y un evento de prueba.
- **Security considerations:** el payload de los eventos se guarda en `event_publication`, así que ningún evento puede llevar secretos (se revisa en cada evento nuevo). Entrega at-least-once: los listeners tienen que ser idempotentes para que un duplicado no cause efectos dobles (T-33, R-13).
- **Dependencies:** Sprint 0.
- **Definition of Done:** sección 4 de [eventos](../architecture/events.md) actualizada con las propiedades reales.

### OW-019 · Proyectos
`feature` · P2 · Milestone: v0.2.0 — Proyectos y monitores · **Planned**

- **Context:** agrupación de monitores dentro de una organización.
- **Objective:** endpoints de proyectos del [catálogo](../api/endpoints-v1.md#proyectos).
- **Tasks:**
  - [ ] Migración `organization_create_projects`, con índice único parcial y `UNIQUE (id, organization_id)` para la FK compuesta de los monitores.
  - [ ] `ProjectService`: cuota, borrado lógico y evento `ProjectDeleted`.
  - [ ] `ProjectDirectory` como API pública.
  - [ ] Filas nuevas en la matriz de autorización (OW-018).
- **Acceptance Criteria:** un nombre duplicado en la misma organización → `409`; se puede repetir en otra organización o después de borrar el proyecto; un no miembro → `404` en todos los endpoints con id.
- **Testing:**
  - Integración: índice único parcial.
  - API y seguridad: endpoints por rol e IDOR.
- **Security considerations:** la creación se autoriza sobre la organización de la ruta con `AccessControl`, nunca con un `organizationId` del cuerpo (mass assignment, T-12).
- **Dependencies:** OW-018.
- **Definition of Done:** `monitorCounts` del proyecto se completa en OW-021.

### OW-020 · `TargetPolicy`: validación de las URL de destino
`security` · P1 · Milestone: v0.2.0 — Proyectos y monitores · **Planned**

- **Context:** capa 1 de la [protección SSRF](../security/ssrf-protection.md#capa-1-validación-al-guardar). Crea el módulo `egress`, por el que pasará todo el HTTP saliente.
- **Objective:** `TargetPolicy` e `IpRangeClassifier` en `egress`.
- **Tasks:**
  - [ ] `IpRangeClassifier` con todos los rangos IPv4 e IPv6, incluidas las IPv4 incrustadas (mapeada, NAT64, 6to4).
  - [ ] Parser estricto: solo `http` y `https`, sin `userinfo`, puertos permitidos, formas de IP no canónicas rechazadas, hostnames prohibidos (`localhost`, `*.internal`, una sola etiqueta), IDN a punycode.
  - [ ] Resolución DNS al guardar, con todas las IP resueltas clasificadas.
  - [ ] Propiedad `allowed-private-cidrs` con sus salvaguardas: nunca abre la metadata cloud y está prohibida en `production`.
- **Acceptance Criteria:** los casos 1 a 15, 20, 21, 24, 25 y 26 de la [tabla de SSRF](../security/ssrf-protection.md#5-casos-de-prueba-obligatorios) dan el resultado esperado.
- **Testing:**
  - Unitarios: `IpRangeClassifierTest` (primera y última IP de cada rango, más una IP pública vecina que debe pasar) y `TargetPolicyTest` con los casos de la tabla.
  - Seguridad: salvaguardas de `allowed-private-cidrs` (prohibida en `production`, metadata siempre bloqueada).
- **Security considerations:** código crítico (T-20). Requiere revisión explícita contra el threat model. La validación al guardar da retroalimentación, pero **no es la barrera definitiva**: la definitiva es la resolución con IP fijada de OW-024.
- **Dependencies:** Sprint 0.
- **Definition of Done:** cada caso de la tabla de SSRF que se decide al guardar tiene su test.

### OW-022 · `SecretCipher` y headers cifrados en los monitores
`security` · P1 · Milestone: v0.2.0 — Proyectos y monitores · **Planned**

- **Context:** los headers de los monitores pueden llevar credenciales de terceros (activo A4). `SecretCipher` estaba en el Sprint 0, pero su primer uso es este y no forma parte de la base.
- **Objective:** `SecretCipher` (AES-256-GCM) en `shared.crypto` y headers cifrados y de solo escritura.
- **Tasks:**
  - [ ] `SecretCipher` con `keyId`, nonce aleatorio de 12 bytes y dato asociado.
  - [ ] `AttributeConverter` JPA que usa `SecretCipher` con el id del monitor como dato asociado.
  - [ ] Validación de headers: lista de prohibidos (incluidos los de metadata cloud), sin CR ni LF, gramática de *token*, 10 headers y 1024 bytes por valor como máximo.
  - [ ] Respuesta con `value: null` y `hasValue: true`; `toString()` sin valores.
- **Acceptance Criteria:** en la base de datos solo hay texto cifrado; ninguna respuesta ni línea de log contiene el valor; un texto cifrado copiado a otro monitor no se descifra.
- **Testing:**
  - Unitarios: `SecretCipherTest` (ida y vuelta, tag alterado, dato asociado distinto, `keyId` desconocido) y validación de headers.
  - Seguridad: forma de la respuesta y captura de logs.
- **Security considerations:** T-14. Nunca se reutiliza un nonce con la misma clave. La rotación está prevista con `keyId`. La clave solo se lee de un secreto.
- **Dependencies:** Sprint 0.
- **Definition of Done:** la sección de cifrado de la arquitectura de seguridad coincide con la implementación.

### OW-021 · Monitores: crear, consultar, editar y cuotas
`feature` · P2 · Milestone: v0.2.0 — Proyectos y monitores · **Planned**

- **Context:** la configuración de qué vigilar ([modelo de dominio](../architecture/domain-model.md#monitor)). La pausa, la reanudación y el borrado van en OW-044.
- **Objective:** `POST /api/v1/projects/{projectId}/monitors`, `GET /api/v1/projects/{projectId}/monitors`, `GET /api/v1/monitors/{monitorId}` y `PATCH /api/v1/monitors/{monitorId}`, con `monitor_state` inicializado.
- **Tasks:**
  - [ ] Migración `monitoring_create_monitors_and_state` (sin `monitor_checks`, que llega en OW-027).
  - [ ] Entidad `Monitor` con sus invariantes (rangos, timeout menor que el intervalo, umbrales).
  - [ ] `MonitorService`: crear (URL validada con `TargetPolicy`, `next_check_at` con jitter inicial) y editar (reprograma si cambia el intervalo).
  - [ ] Filtros, ordenación con lista blanca y `monitorCounts` del proyecto.
  - [ ] Filas nuevas en la matriz de autorización.
- **Acceptance Criteria:**
  - Un `MEMBER` crea `GET https://api.example.com/health` cada 60 s; un `VIEWER` recibe `403`.
  - Una URL de la tabla de SSRF → `422 target-not-allowed`.
  - El monitor 51 de una organización → `422 quota-exceeded`.
  - `timeoutMs` mayor o igual que el intervalo → `400`.
  - `sort=url` (fuera de la lista blanca) → `400 invalid-parameter`.
- **Testing:**
  - Unitarios: invariantes de `Monitor` y cálculo del jitter con `RandomGenerator` fijo.
  - API y seguridad: endpoints por rol, IDOR y `projectId` solo desde la ruta.
- **Security considerations:** URL validada con `TargetPolicy` (T-20); la ordenación por lista blanca evita inyecciones en la consulta (T-13); la cuota limita el uso del motor contra terceros (T-26).
- **Dependencies:** OW-019, OW-020, OW-022.
- **Definition of Done:** el ejemplo de CharityLink del README se puede crear entero por la API.

### OW-044 · Monitores: pausar, reanudar, borrar y limpieza por `ProjectDeleted`
`feature` · P2 · Milestone: v0.2.0 — Proyectos y monitores · **Planned**

- **Context:** acciones de ciclo de vida del monitor, separadas de OW-021 para que ninguna de las dos issues sea demasiado grande.
- **Objective:** `POST /api/v1/monitors/{monitorId}/pause`, `POST /api/v1/monitors/{monitorId}/resume`, `DELETE /api/v1/monitors/{monitorId}` y el borrado de monitores cuando se borra su proyecto.
- **Tasks:**
  - [ ] Pausa: `PAUSED` y `next_check_at = NULL`; evento `MonitorPaused`.
  - [ ] Reanudación: `PENDING` y `next_check_at` con jitter.
  - [ ] Borrado lógico; evento `MonitorDeleted`.
  - [ ] Listener asíncrono de `ProjectDeleted` (`@ApplicationModuleListener`, con el registro de OW-034).
- **Acceptance Criteria:** pausar un monitor pausado → `409`; reanudar uno activo → `409`; al borrar un proyecto, todos sus monitores quedan borrados y sin programar, también si la aplicación se reinicia entre el commit y el listener.
- **Testing:**
  - Unitarios: transiciones de pausa y reanudación.
  - Módulo: `@ApplicationModuleTest` con `Scenario`: `ProjectDeleted` → monitores borrados.
  - API y seguridad: endpoints por rol e IDOR.
- **Security considerations:** integridad: un monitor borrado o pausado no debe seguir haciendo peticiones (`next_check_at = NULL` y la restricción `ck_monitor_state_paused`). El listener es idempotente: un `ProjectDeleted` duplicado no hace nada más.
- **Dependencies:** OW-021, OW-034.
- **Definition of Done:** los eventos `MonitorPaused` y `MonitorDeleted` están documentados; sus listeners en `incident` llegan en OW-032.

### OW-023 · Tests de aislamiento multi-tenant de la Fase 2
`testing` `security` · P2 · Milestone: v0.2.0 — Proyectos y monitores · **Cerrada**

Fusionada en OW-019, OW-021 y OW-044: los tests de IDOR de cada endpoint forman parte de la [Definition of Done](definition-of-done.md) de la issue que crea el endpoint, y OW-018 hace imposible olvidarlos.

---

## v0.3.0 — Motor de monitoreo

### OW-024 · `GuardedDnsResolver` y cliente HTTP saliente endurecido
`security` · P1 · Milestone: v0.3.0 — Motor de monitoreo · **Planned**

- **Context:** capas 2 a 5 de la protección SSRF. Es la defensa contra el DNS rebinding.
- **Objective:** `EgressHttpClients` construye clientes Apache HttpClient 5 con el resolver protegido y todas las restricciones; nadie más construye clientes HTTP.
- **Tasks:**
  - [ ] Dependencia de Apache HttpClient 5.
  - [ ] `GuardedDnsResolver` y `BlockedTargetException`.
  - [ ] Cliente sin proxy del entorno, sin reintentos, sin redirects automáticos, sin cookies y sin compresión; límites de línea (8 KiB) y número (100) de headers de respuesta.
  - [ ] Regla ArchUnit: solo `egress` construye clientes HTTP.
- **Acceptance Criteria:** los casos 16, 17, 22 y 23 de la [tabla de SSRF](../security/ssrf-protection.md#5-casos-de-prueba-obligatorios); una URL con IP literal pasa por `resolve()` (test); una clase de `monitoring` que instancia `HttpClients` hace fallar el build.
- **Testing:**
  - Unitarios: `GuardedDnsResolver` con un resolver falso (IP mixtas, rebinding).
  - Integración: cliente contra WireMock (goteo, headers enormes).
  - Arquitectura: la regla ArchUnit.
- **Security considerations:** T-20, T-21 y T-23. Un proxy del entorno saltaría el resolver: por eso se desactivan las propiedades del sistema. Si una versión futura del cliente dejara de pasar por el resolver, el test de la IP literal lo detecta.
- **Dependencies:** OW-020.
- **Definition of Done:** el documento de SSRF coincide con la implementación.

### OW-025 · `HttpMonitorClient` con Apache HttpClient 5
`feature` `security` · P1 · Milestone: v0.3.0 — Motor de monitoreo · **Planned**

- **Context:** separar observar de juzgar ([motor](../architecture/monitoring-engine.md#5-httpmonitorclient)).
- **Objective:** `ApacheHttpMonitorClient` con deadline total, redirects manuales y clasificación de fallos.
- **Tasks:**
  - [ ] `ProbeRequest` y `HttpObservation` (sealed).
  - [ ] Deadline total con `cancel()` programado (`timeoutMs` + `deadline-grace`).
  - [ ] Redirects manuales: revalidación por salto, cambio de método, headers solo al mismo origen, 5 saltos como máximo y detección de bucles.
  - [ ] Mapeo de excepción a `FailureReason`.
  - [ ] Cierre de la conexión tras recibir los headers (el cuerpo no se lee).
- **Acceptance Criteria:** los casos 18, 19 y 22 de la tabla de SSRF; un destino que tarda más que `timeoutMs` devuelve `TIMEOUT` en menos de `timeoutMs` + 500 ms; cada fila de la [tabla de clasificación](../architecture/monitoring-engine.md#8-clasificación-de-fallos) produce su `FailureReason`.
- **Testing:**
  - Integración: `ApacheHttpMonitorClientIT` contra WireMock (códigos, retrasos, redirects, bucles, más de 5 saltos, TLS autofirmado, `Location` inválido).
  - Seguridad: redirect a otro origen sin `Authorization`; redirect hacia `169.254.169.254`.
- **Security considerations:** T-22 (redirect hacia la red interna), T-27 (credenciales reenviadas a otro host), T-28 (el cuerpo no se guarda ni se muestra). Disponibilidad: el deadline impide que un destino hostil retenga un permiso más de `timeoutMs`.
- **Dependencies:** OW-024.
- **Definition of Done:** la tabla de clasificación del documento del motor está verificada por los tests.

### OW-026 · Scheduler con `SKIP LOCKED` y dispatcher con virtual threads
`feature` `architecture` · P1 · Milestone: v0.3.0 — Motor de monitoreo · **Planned**

- **Context:** [ADR-006](../adr/ADR-006-check-scheduling.md) y [ADR-007](../adr/ADR-007-http-client-and-concurrency.md). Incluye los tests de concurrencia que antes estaban en OW-031.
- **Objective:** `CheckClaimer` y `CheckDispatcher` con semáforo, sin catch-up y con apagado ordenado.
- **Tasks:**
  - [ ] Consulta de claim (CTE con `FOR UPDATE SKIP LOCKED`).
  - [ ] Dispatcher con `Semaphore` y executor de virtual threads; reclama como mucho los permisos libres.
  - [ ] `opswatch.monitoring.engine.enabled`.
  - [ ] Apagado ordenado (`SmartLifecycle`).
- **Acceptance Criteria:**
  - 4 claimers en paralelo sobre 1 000 monitores vencidos, 20 rondas: cada monitor reclamado **exactamente una vez** por ronda (0 duplicados, 0 omitidos).
  - Un monitor atrasado más de un intervalo se ejecuta una vez y salta al siguiente intervalo (sin catch-up).
  - Con el semáforo lleno no se reclama nada.
  - El plan de la consulta de claim usa `ix_monitor_state_due`.
- **Testing:**
  - Unitarios: cálculo del siguiente `next_check_at` con `Clock` fijo.
  - Integración (concurrencia): `CheckClaimerConcurrencyIT` con PostgreSQL real.
  - Integración: dispatcher con un `HttpMonitorClient` falso; `EXPLAIN` del claim.
- **Security considerations:** una ejecución duplicada no es solo un fallo de correctitud: duplica el tráfico hacia terceros (T-26) y puede abrir incidentes falsos. El semáforo evita agotar hilos y conexiones (R-11, R-12).
- **Dependencies:** OW-025.
- **Definition of Done:** el algoritmo del documento del motor coincide con la implementación.

### OW-027 · Registro de resultados y máquina de estados del monitor
`feature` · P1 · Milestone: v0.3.0 — Motor de monitoreo · **Planned**

- **Context:** la [tabla de transiciones](../architecture/domain-model.md#monitorstate) es el núcleo de la correctitud. Incluye la carrera de la pausa que antes estaba en OW-031.
- **Objective:** `CheckEvaluator`, `StateTransition` y `CheckResultRecorder`, con los eventos `MonitorWentDown` y `MonitorRecovered`.
- **Tasks:**
  - [ ] Migración `monitoring_create_monitor_checks`, con PK `(monitor_id, checked_at)` y BRIN.
  - [ ] `CheckEvaluator` y `StateTransition` como funciones puras.
  - [ ] `CheckResultRecorder`: `FOR UPDATE` sobre el estado, inserción con `JdbcClient`, transición y evento.
  - [ ] Los errores internos no cuentan como check.
- **Acceptance Criteria:**
  - 3 fallos consecutivos → `DOWN` y un `MonitorWentDown`; 2 éxitos → `UP` y un `MonitorRecovered`.
  - Pausa concurrente con el registro de un resultado (50 repeticiones): el estado final es siempre `PAUSED` y el check queda guardado.
  - Una `RuntimeException` propia no crea check ni cambia el estado, y cuenta en `outcome="ERROR"`.
- **Testing:**
  - Unitarios: `StateTransitionTest` con cada fila de la tabla y `CheckEvaluatorTest`.
  - Integración (concurrencia): `CheckResultRecorderIT`, incluida la carrera de la pausa.
- **Security considerations:** integridad del estado: `FOR UPDATE` serializa las transiciones de cada monitor. `error_detail` es un texto propio, nunca contenido de la respuesta (T-28).
- **Dependencies:** OW-026, OW-044.
- **Definition of Done:** cada fila de la tabla de transiciones del modelo de dominio tiene su test.

### OW-028 · Consulta de checks (cursor) y estadísticas
`feature` · P2 · Milestone: v0.3.0 — Motor de monitoreo · **Planned**

- **Context:** historial y uptime por la API.
- **Objective:** `GET /api/v1/monitors/{monitorId}/checks` y `GET /api/v1/monitors/{monitorId}/stats`.
- **Tasks:**
  - [ ] `CursorPage` con un cursor opaco validado.
  - [ ] Consulta de estadísticas (`FILTER` y `percentile_cont`).
  - [ ] Ventanas `24h`, `7d` y `30d`.
- **Acceptance Criteria:** con datos conocidos, el uptime y los percentiles coinciden con los calculados a mano; un cursor manipulado → `400`; `limit=201` → `400`.
- **Testing:**
  - Unitarios: codificación del cursor.
  - Integración: `MonitorStatsIT` con datos sembrados.
  - API y seguridad: IDOR en los dos endpoints.
- **Security considerations:** un cursor manipulado no puede saltar a datos de otro monitor: el cursor solo contiene una fecha y la consulta siempre filtra por el `monitorId` autorizado. `limit` acotado contra consultas caras (disponibilidad).
- **Dependencies:** OW-027.
- **Definition of Done:** tiempo de `stats?window=30d` con 86 400 filas anotado.

### OW-029 · Job de retención
`feature` `performance` · P2 · Milestone: v0.3.0 — Motor de monitoreo · **Planned**

- **Context:** crecimiento de `monitor_checks` ([retención](../database/data-retention.md)).
- **Objective:** purga diaria en lotes, con advisory lock.
- **Tasks:**
  - [ ] `RetentionJob` para checks, refresh tokens y checks de monitores borrados.
  - [ ] `pg_try_advisory_lock` para que solo corra en una instancia.
  - [ ] Métricas de filas borradas y de duración.
- **Acceptance Criteria:** borra solo lo anterior a la fecha de corte; dos instancias a la vez → solo una purga; lotes de 10 000 filas como máximo por transacción.
- **Testing:**
  - Integración: `RetentionJobIT`, incluido el caso de dos instancias.
- **Security considerations:** integridad y disponibilidad: un error en la fecha de corte borraría historial válido (el test lo cubre), y un `DELETE` masivo sin lotes bloquearía la tabla. Sin dato sensible nuevo.
- **Dependencies:** OW-027.
- **Definition of Done:** propiedades de retención en el catálogo.

### OW-030 · Métricas del motor con Micrometer
`devops` `performance` · P2 · Milestone: v0.3.0 — Motor de monitoreo · **Planned**

- **Context:** sin métricas no hay evidencia para las decisiones de las Fases 7 a 10.
- **Objective:** las métricas de la Fase 3 de [observabilidad](../devops/observability.md#métricas-propias) en `/actuator/prometheus`.
- **Tasks:**
  - [ ] Registro de Prometheus de Micrometer.
  - [ ] Contadores, histogramas con buckets explícitos y gauges recalculados de forma periódica.
  - [ ] Test de que ninguna métrica lleva etiquetas de alta cardinalidad.
  - [ ] Medición informal con 100 y 1 000 monitores contra un destino local.
- **Acceptance Criteria:** después de ejecutar checks, las métricas aparecen con los valores esperados; ninguna etiqueta tiene `monitorId`, `organizationId` ni URL.
- **Testing:**
  - Integración: `EngineMetricsIT` y el test de cardinalidad.
  - Rendimiento: la medición informal (no es un benchmark formal).
- **Security considerations:** `/actuator/prometheus` solo en el puerto de management, que no se publica; sin identificadores de clientes en las métricas (fuga de información y cardinalidad).
- **Dependencies:** OW-026, OW-027.
- **Definition of Done:** resultado de la medición informal en `docs/performance/results/`.

### OW-031 · Tests de concurrencia del motor
`testing` · P1 · Milestone: v0.3.0 — Motor de monitoreo · **Cerrada**

Fusionada en OW-026 (claimers concurrentes) y OW-027 (pausa concurrente con el registro de un resultado): un test de concurrencia pertenece a la issue que introduce la concurrencia.

---

## v0.4.0 — Incidentes y notificaciones

### OW-032 · Incidentes: apertura y resolución automáticas
`feature` · P1 · Milestone: v0.4.0 — Incidentes y notificaciones · **Planned**

- **Context:** [ciclo de vida de incidentes](../architecture/incident-lifecycle.md) y [eventos](../architecture/events.md).
- **Objective:** el listener síncrono de `incident` abre y resuelve incidentes a partir de los eventos del monitor.
- **Tasks:**
  - [ ] Migración `incident_create_incidents_and_timeline`, con el índice único parcial.
  - [ ] `MonitorEventsListener` (`@EventListener`, en la misma transacción) para `MonitorWentDown`, `MonitorRecovered`, `MonitorPaused` y `MonitorDeleted`.
  - [ ] Apertura idempotente y timeline.
  - [ ] Eventos `IncidentOpened` e `IncidentResolved`.
- **Acceptance Criteria:** una caída = un incidente; pausar un monitor caído lo resuelve con `MONITOR_PAUSED`; dos `MonitorWentDown` seguidos no crean dos incidentes; insertar a mano un segundo incidente activo para el mismo monitor viola `ux_incidents_one_active_per_monitor`.
- **Testing:**
  - Módulo: `@ApplicationModuleTest` con `Scenario` (`MonitorWentDown` → `IncidentOpened`).
  - Integración: la restricción única (`IncidentRepositoryIT`) y los eventos duplicados.
- **Security considerations:** integridad del estado entre módulos: el listener síncrono y el índice único garantizan la invariante aunque haya duplicados. Los eventos llevan solo ids, el nombre del monitor y la causa, nunca la URL ni los headers.
- **Dependencies:** OW-027.
- **Definition of Done:** la invariante "un incidente activo por monitor" está probada en la base de datos y en la aplicación.

### OW-033 · Acknowledge, listados y timeline
`feature` · P2 · Milestone: v0.4.0 — Incidentes y notificaciones · **Planned**

- **Context:** la interacción humana con los incidentes. No hay resolución manual en V1 ([por qué](../architecture/incident-lifecycle.md#por-qué-no-hay-resolución-manual-r6)).
- **Objective:** `GET /api/v1/organizations/{orgId}/incidents`, `GET /api/v1/incidents/{incidentId}` y `POST /api/v1/incidents/{incidentId}/acknowledge`.
- **Tasks:**
  - [ ] Acknowledge con nota opcional.
  - [ ] Listado por organización con filtros.
  - [ ] Detalle con el timeline.
  - [ ] Filas nuevas en la matriz de autorización.
- **Acceptance Criteria:** acknowledge sobre `OPEN` → `ACKNOWLEDGED`; sobre `RESOLVED` → `409`; un `VIEWER` → `403`; acknowledge a la vez que la recuperación: una de las dos operaciones falla con conflicto y el estado final es coherente.
- **Testing:**
  - API y seguridad: endpoints por rol e IDOR.
  - Integración (concurrencia): acknowledge frente a la recuperación.
- **Security considerations:** la nota tiene como máximo 500 caracteres y se escapa al mostrarla (XSS en un frontend futuro); `@Version` evita perder actualizaciones.
- **Dependencies:** OW-032.
- **Definition of Done:** el catálogo de endpoints coincide con la implementación.

### OW-035 · Canales de notificación (email y webhook)
`feature` `security` · P2 · Milestone: v0.4.0 — Incidentes y notificaciones · **Planned**

- **Context:** a dónde avisar ([modelo de dominio](../architecture/domain-model.md#notificationchannel)).
- **Objective:** CRUD de canales con la configuración cifrada, un secreto de firma que se muestra una sola vez y el endpoint de prueba.
- **Tasks:**
  - [ ] Migración `notification_create_channels_and_deliveries`.
  - [ ] Configuración cifrada con `SecretCipher`.
  - [ ] Webhook: `TargetPolicy` solo con `https`; generación y rotación del secreto.
  - [ ] `POST /api/v1/notification-channels/{channelId}/test` con rate limit.
  - [ ] Mailpit en Compose (profile `mail`).
  - [ ] Filas nuevas en la matriz de autorización.
- **Acceptance Criteria:** un webhook `http://` → `422`; el secreto solo aparece en la respuesta de creación y en la de rotación; el sexto envío de prueba en un minuto → `429`.
- **Testing:**
  - API y seguridad: endpoints por rol, IDOR y enmascarado de la configuración.
  - Seguridad: caso 26 de la tabla de SSRF (webhook `http`) y URL de webhook hacia una red privada.
- **Security considerations:** T-30 (SSRF por webhooks), T-32 (lectura de secretos) y T-35 (spam con el endpoint de prueba).
- **Dependencies:** OW-018, OW-022, OW-024.
- **Definition of Done:** el catálogo de endpoints coincide con la implementación.

### OW-036 · Entrega de notificaciones: listener, worker con reintentos y email
`feature` · P2 · Milestone: v0.4.0 — Incidentes y notificaciones · **Planned**

- **Context:** efecto lateral fiable a partir de los eventos de incidentes. Los webhooks firmados van en OW-043.
- **Objective:** listener asíncrono que crea las entregas y worker que las envía por email con backoff.
- **Tasks:**
  - [ ] `IncidentEventsListener` (`@ApplicationModuleListener`, registro de OW-034).
  - [ ] `DeliveryWorker`: `SKIP LOCKED`, backoff de 0 s, 30 s, 2 min, 10 min, 30 min y 1 h, 6 intentos como máximo.
  - [ ] `EmailSender` con Spring Mail y plantillas que escapan HTML.
  - [ ] `GET /api/v1/notification-channels/{channelId}/deliveries`.
- **Acceptance Criteria:**
  - Una caída de 10 minutos → exactamente un email de apertura y uno de resolución por canal.
  - Con el SMTP caído, las entregas se reintentan y acaban en `SENT` al volver, o en `FAILED` tras 6 intentos.
  - Un `IncidentOpened` duplicado no crea una segunda entrega.
  - Si la aplicación se reinicia entre la apertura del incidente y la creación de las entregas, se crean al reiniciar, una sola vez.
- **Testing:**
  - Integración: worker contra un SMTP falso (fallo, reintentos, `FAILED`).
  - Módulo: idempotencia ante eventos duplicados.
  - Integración: reinicio con publicaciones pendientes.
- **Security considerations:** T-33 (un proveedor lento no bloquea las entregas: timeout y worker aparte) y T-34 (inyección en las plantillas: escapado). Ningún I/O externo dentro de transacciones de negocio.
- **Dependencies:** OW-032, OW-034, OW-035.
- **Definition of Done:** el flujo de eventos de `events.md` coincide con la implementación.

### OW-043 · Webhooks firmados con HMAC
`feature` `security` · P2 · Milestone: v0.4.0 — Incidentes y notificaciones · **Planned**

- **Context:** separado de OW-036 para que la entrega por webhook y su firma tengan su propia revisión de seguridad.
- **Objective:** `WebhookSender` a través de `egress`, con cuerpo generado por OpsWatch y firma verificable.
- **Tasks:**
  - [ ] `WebhookSender`: `POST` JSON por el cliente de `egress`, timeout de 5 s, solo `https` también en los redirects.
  - [ ] `X-OpsWatch-Signature: t=<timestamp>,v1=<HMAC-SHA256>` y `X-OpsWatch-Webhook-Version: 1`.
  - [ ] Guía breve para los receptores: cómo verificar la firma y rechazar marcas de tiempo antiguas.
- **Acceptance Criteria:** un verificador independiente escrito en el test valida la firma con el secreto del canal; un webhook que redirige a `http://` o a una IP privada falla sin enviar el cuerpo; un receptor que tarda más de 5 s cuenta como intento fallido.
- **Testing:**
  - Integración: `WebhookSenderIT` contra WireMock.
  - Seguridad: firma, redirects prohibidos, SSRF por redirect.
- **Security considerations:** T-30 (SSRF), T-31 (suplantación de OpsWatch ante el receptor). La marca de tiempo en la firma permite al receptor rechazar repeticiones.
- **Dependencies:** OW-024, OW-036.
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
