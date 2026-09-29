# Sprint 0: Fundaciones

Estado: **en curso, es el foco actual** · Última revisión: 2026-09-28 · Milestone: Sprint 0 — Fundaciones · Issues: OW-001 a OW-010 (#2 a #11) del [backlog](backlog.md)

## Objetivo

Terminar con un repositorio donde añadir la primera funcionalidad (Fase 1) sea solo escribir la funcionalidad: estructura, base de datos, migraciones, errores, logs, seguridad por defecto, tests y CI ya resueltos.

**Fuera del Sprint 0:** entidades de negocio, endpoints de negocio, el motor, Redis, un broker, el despliegue en un servidor.

## Resultado esperado

```text
opswatch/
├── .github/
│   ├── workflows/ci.yml
│   ├── dependabot.yml
│   ├── pull_request_template.md
│   └── ISSUE_TEMPLATE/ (feature, bug, task)
├── .mvn/wrapper/, mvnw, mvnw.cmd
├── docs/ (esta documentación)
├── scripts/dev-keys.sh
├── src/main/java/io/github/ricardoord/opswatch/
│   ├── OpsWatchApplication.java
│   ├── shared/        error/, web/, security/, time/   ← con contenido (crypto/ llega en OW-022)
│   ├── egress/        package-info.java                         ← vacío, declarado
│   ├── identity/      package-info.java, security/SecurityConfiguration.java (baseline)
│   ├── organization/  package-info.java
│   ├── monitoring/    package-info.java
│   ├── incident/      package-info.java
│   └── notification/  package-info.java
├── src/main/resources/
│   ├── application.yml, application-local.yml, application-test.yml,
│   │   application-deployed.yml, application-staging.yml, application-production.yml
│   └── db/migration/  (vacía: la primera migración llega en la Fase 1)
├── src/test/java/…    ModularityTests, ApplicationStartupIT, ProblemDetailsIT,
│                      RequestIdFilterTest, ProductionGuardrailsTest, PostgresTestcontainer
├── .dockerignore, .gitignore, .editorconfig, .env.example
├── Dockerfile
├── docker-compose.yml
├── pom.xml
└── README.md
```

## Tareas

### 1. Repositorio (OW-001)

- [x] `git init`, rama `main` y repositorio público `RicardoOrd/opswatch` (2026-09-28).
- [ ] `.gitignore` (Java, Maven, IDE, `.env`, `secrets/`, `*.pem`), `.editorconfig`.
- [x] Protección de `main`: PR obligatorio, historial lineal, sin force-push, también para admins. Solo squash y borrado automático de ramas.
- [ ] Checks de CI obligatorios en la protección de `main` (se añaden en OW-010, cuando exista el workflow).
- [ ] Plantilla de PR con la checklist de la [Definition of Done](definition-of-done.md) y plantillas de issue (feature, bug, release).
- [ ] `dependabot.yml` para Maven, Docker y GitHub Actions.
- [x] Etiquetas de tipo (`feature`, `architecture`, `security`, `testing`, `devops`, `documentation`, `performance`, `refactor`, `bug`) y de prioridad (`P0` a `P3`). Las fases van en milestones, no en etiquetas.
- [x] Milestones de V1 (Sprint 0 a v1.0.0) según el [roadmap](roadmap.md#milestones).
- [ ] Verificar que secret scanning y push protection están activos.

### 2. Proyecto Spring Boot (OW-002)

Generado con Spring Initializr y **versiones fijadas** en el `pom.xml`:

| Capacidad | Para qué | Por qué ahora |
|---|---|---|
| Java 25 | — | [ADR-007](../adr/ADR-007-http-client-and-concurrency.md) |
| Spring Boot 4.x (última estable) | — | — |
| Spring MVC (starter web) | API REST | Base |
| Bean Validation | Validación de DTOs | Base de la validación desde el primer endpoint |
| Spring Data JPA | Persistencia | La base de datos se conecta en este sprint |
| Driver de PostgreSQL | — | — |
| Flyway + `flyway-database-postgresql` | Migraciones | Obligatorio desde el primer esquema |
| Spring Security | Denegar por defecto | La seguridad es la base, no un añadido |
| Actuator | Healthchecks de Docker | El Dockerfile y Compose los necesitan |
| Spring Modulith (core + test) | Límites de módulo | `verify()` desde el primer commit |
| springdoc-openapi | OpenAPI y Swagger UI | Documentar desde el primer endpoint |
| Test: starter de test de Spring Boot, soporte de Testcontainers, Testcontainers PostgreSQL | Tests de integración | La infraestructura de tests es parte de la base |

**No se añaden todavía** (cada una entra en la fase que la necesita): OAuth2 Resource Server (Fase 1), Bucket4j (Fase 1), Apache HttpClient 5 (Fase 3), WireMock (Fase 3), el registro de Prometheus de Micrometer (Fase 3), el registro de eventos JPA/JDBC de Modulith (Fase 2, OW-034), Spring Mail (Fase 4).

Plugins de Maven: `spring-boot-maven-plugin` (con `finalName` `opswatch`), Surefire (`*Test`), Failsafe (`*IT`), Spotless con palantir-java-format, JaCoCo (solo informe), Maven Enforcer (versión de Java y de Maven, convergencia de dependencias), compilador con `-Xlint:all -Werror` y `-parameters`.

Verificaciones del Sprint 0 que la documentación dejó abiertas:

- [x] Nombres exactos de los starters en Spring Boot 4.1.1: `spring-boot-starter-webmvc` (no `-web`), `spring-boot-starter-flyway`, y un starter de test por módulo (`spring-boot-starter-webmvc-test`, `-security-test`, `-data-jpa-test`, `-flyway-test`).
- [x] Generador de UUIDv7: Hibernate 7.4.5 incluye `@UuidGenerator(style = UuidGenerator.Style.VERSION_7)`. No hace falta un generador propio ([base de datos](../database/database-design.md#3-uuid-o-bigint)).
- [x] springdoc 3.1.0, compatible con Boot `[4.0.0, 4.2.0-M1)` según Spring Initializr.
- [x] Testcontainers 2.0.5 (gestionado por Boot): la clase es `org.testcontainers.postgresql.PostgreSQLContainer`, sin genéricos, en el artefacto `testcontainers-postgresql`.
- [x] Ruta del volumen de datos de la imagen `postgres:18` (OW-004): `VOLUME /var/lib/postgresql` y `PGDATA=/var/lib/postgresql/18/docker`. El volumen de Compose se monta en `/var/lib/postgresql`.
- [x] `spring.threads.virtual.enabled=true` (OW-006): Java 25 no fija virtual threads en `synchronized`, así que no hace falta dimensionar el pool de hilos de Tomcat. El límite real pasa a ser el pool de conexiones, que es observable ([entornos](../devops/environments.md#spring-y-librerías-valores-fijados)).
- [x] Spring Framework 7.0.9 incluye versionado nativo de API (`ApiVersionConfigurer`). **Decisión:** no se activa mientras haya una sola versión; `/api/v1` es un prefijo fijo de las rutas ([guía de API](../api/api-guidelines.md#2-url-y-versionado)).

Versiones fijadas en OW-002 (2026-09-28): Java 25 (Temurin 25.0.4), Spring Boot 4.1.1, Spring Modulith 2.1.1, springdoc 3.1.0. Gestionadas por Boot: Hibernate 7.4.5, Flyway 12.4.0, Testcontainers 2.0.5 y driver PostgreSQL 42.7.13. Herramientas: Maven 3.9.16 (wrapper), Spotless 3.10.3 con palantir-java-format 2.100.0 y JaCoCo 0.8.15.

### 3. Estructura de paquetes y Modulith (OW-003)

- [ ] Los siete paquetes de módulo con `package-info.java`, `@ApplicationModule(allowedDependencies = …)` según [modules.md](../architecture/modules.md#4-reglas-de-dependencia) y `@NullMarked`.
- [ ] `shared` como módulo `OPEN`.
- [ ] `ModularityTests` con `verify()` y `Documenter`.
- [ ] Reglas ArchUnit de la [estrategia de testing](../testing/testing-strategy.md#arquitectura) (las que se pueden escribir ya: sin inyección por campo, sin `Instant.now()`, sin `@Transactional` en `web`).

### 4. PostgreSQL, Flyway y Docker Compose (OW-004)

- [x] `docker-compose.yml` con `postgres:18-alpine` (fijado por digest), healthcheck y puerto solo en loopback ([docker.md](../devops/docker.md)).
- [x] `application.yml` con `ddl-auto=validate`, `open-in-view=false`, `clean-disabled=true` y zona UTC.
- [x] Flyway configurado con `db/migration` vacía.
- [x] `.env.example` y `scripts/dev-keys.sh`, que genera las claves JWT y de cifrado de desarrollo en `secrets/` y crea `.env` ([entornos](../devops/environments.md#env-y-perfil-local)).

### 5. Manejo de errores (OW-005)

- [ ] Jerarquía `DomainException` en `shared.error` ([estándares](../development/code-standards.md#excepciones)).
- [ ] `@RestControllerAdvice` que produce Problem Details con `code` y `requestId` para: excepciones de dominio, validación (`errors[]`), JSON malformado, propiedades desconocidas, tipos inválidos, `405`, `404` de rutas inexistentes y `500` genérico.
- [ ] Los errores de Spring Security (`401` y `403`) con el mismo formato (`AuthenticationEntryPoint` y `AccessDeniedHandler` propios).
- [ ] `PageResponse<T>` y el esqueleto de `CursorPage<T>`.

### 6. Logging y perfiles (OW-006)

- [ ] `RequestIdFilter`: acepta un `X-Request-Id` válido o lo genera, lo pone en el MDC y lo devuelve en la respuesta.
- [ ] Logging estructurado JSON (ECS) en `staging` y `production`, y texto en `local`.
- [ ] Perfiles `local`, `test`, `staging` y `production` con la estructura de [entornos](../devops/environments.md).
- [ ] Salvaguardas de arranque: la estructura del validador y las reglas aplicables ya (CORS, `ddl-auto`, `clean-disabled`, secretos obligatorios, Swagger).
- [ ] `ClockConfiguration` (bean `Clock` UTC) y `RandomGenerator`.

### 7. Infraestructura de tests (OW-007)

- [ ] `PostgresTestcontainer` con `@ServiceConnection`, compartido por toda la JVM.
- [ ] Separación Surefire (`*Test`) y Failsafe (`*IT`).
- [ ] `ApplicationStartupIT`: el contexto arranca con PostgreSQL real y Flyway.
- [ ] Builders de test base y la convención de nombres documentada.
- [ ] Instrucciones para reutilizar el contenedor en local.

### 8. Seguridad base (OW-008)

- [ ] `SecurityFilterChain`: todo `/api/**` exige autenticación; públicos solo `/api/v1/auth/**` (sin endpoints todavía), OpenAPI en `local` y `staging`; sesiones `STATELESS`; CSRF desactivado para la API con Bearer (documentado); formulario de login y HTTP Basic desactivados.
- [ ] Cabeceras de seguridad de [arquitectura de seguridad](../security/security-architecture.md#cabeceras-de-seguridad-respuestas-de-la-api).
- [ ] CORS desde `opswatch.security.cors.allowed-origins`.
- [ ] Actuator en el puerto 8081 con solo `health` e `info` (y `prometheus` preparado para la Fase 3).

### 9. Docker (OW-009)

- [ ] `Dockerfile` multi-stage, no root, con capas y healthcheck ([docker.md](../devops/docker.md)).
- [ ] `.dockerignore`.
- [ ] Servicio `app` en Compose con el profile `app`, `read_only`, `cap_drop` y `no-new-privileges`.
- [ ] Tamaño de la imagen medido y anotado.

### 10. CI (OW-010)

- [ ] `ci.yml` con los jobs `build`, `secrets-scan` e `image` ([CI/CD](../devops/ci-cd.md)), acciones fijadas por SHA y permisos mínimos.
- [ ] Trivy sobre la imagen.
- [ ] Publicación en GHCR en los push a `main`.
- [ ] Checks `build` e `image` obligatorios en la protección de `main`.

### 11. Documentación

- [x] Esta documentación en `docs/`, publicada en el PR #1 (OW-011, cerrada).
- [ ] README: la sección de cómo ejecutarlo pasa de objetivo a instrucciones reales y comprobadas (DoD de OW-004).
- [ ] ADR 001 a 008 revisados con las versiones fijadas (DoD de OW-002).

## Definition of Done del Sprint 0

- [ ] `./mvnw verify` en verde en local (Windows) y en CI (Linux).
- [ ] `docker compose up -d postgres` más la aplicación desde el IDE con el perfil `local`: arranca sin errores.
- [ ] `docker compose --profile app up --build`: los dos contenedores quedan *healthy*.
- [ ] `curl localhost:8081/actuator/health/readiness` da `{"status":"UP"}`.
- [ ] `curl -i localhost:8080/api/v1/anything` da `401` con `application/problem+json` y `X-Request-Id`.
- [ ] Un PR de prueba con un fichero mal formateado falla en CI. Uno con una clave privada falsa falla en gitleaks.
- [ ] `verify()` falla si se añade, en una rama de prueba, una dependencia de `monitoring` hacia `incident`. Se comprueba y se descarta la rama.
- [ ] La imagen no corre como root (`docker run --rm opswatch:local id`).
- [ ] Ningún secreto en Git, en la imagen (`docker history`) ni en los `application*.yml`.

## Demo de cierre

1. Clonar el repositorio en limpio y seguir el README hasta tener todo en marcha.
2. Mostrar el `401` con Problem Details y el `X-Request-Id`.
3. Mostrar el pipeline en verde y la imagen en GHCR.
4. Romper un límite de módulo en una rama y ver fallar el build.
