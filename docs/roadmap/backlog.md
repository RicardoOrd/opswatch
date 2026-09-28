# Backlog inicial

Estado: listo para crear como issues en GitHub · Última revisión: 2026-09-28

Cubre las Fases 0 a 4 en detalle y la Fase 5 de forma resumida. Las fases posteriores se detallan al cerrar la anterior: planificar hoy el trabajo de las Fases 7 a 12 sería inventar.

**Etiquetas:** `feature`, `architecture`, `security`, `testing`, `devops`, `documentation`, `performance`, `refactor`, y `phase-0` … `phase-5`.

**Definition of Done:** todas las issues cumplen la [DoD global](definition-of-done.md). El campo *Definition of Done* de cada issue añade solo lo específico.

---

## Fase 0: Fundaciones

### OW-001 · Inicializar el repositorio y sus convenciones
`devops` `phase-0`

- **Context:** no existe repositorio. Todo cambio debe entrar por PR con CI.
- **Objective:** repositorio en GitHub con `main` protegida y convenciones listas.
- **Tasks:**
  - [ ] `git init`, repositorio en GitHub, primer commit con `.gitignore`, `.editorconfig` y `docs/`.
  - [ ] Protección de `main` (PR obligatorio, historial lineal, solo squash, sin force-push).
  - [ ] Plantilla de PR con la checklist de la DoD. Plantillas de issue (feature, bug, task).
  - [ ] `dependabot.yml` (Maven, Docker, Actions).
  - [ ] Etiquetas del backlog.
  - [ ] Secret scanning y push protection.
- **Acceptance Criteria:** un push directo a `main` es rechazado; un PR muestra la plantilla; Dependabot aparece configurado.
- **Testing:** push directo de prueba (rechazado).
- **Security considerations:** `.gitignore` con `.env`, `secrets/`, `*.pem` y `*.key` **antes** de crear cualquier secreto local.
- **Dependencies:** ninguna.
- **Definition of Done:** configuración de GitHub documentada en [CI/CD](../devops/ci-cd.md).

### OW-002 · Generar el proyecto Spring Boot con las dependencias base
`architecture` `phase-0`

- **Context:** hace falta el esqueleto con las versiones fijadas y solo las dependencias justificadas para el Sprint 0.
- **Objective:** proyecto Maven que compila, con el wrapper, sin warnings y con las verificaciones abiertas resueltas.
- **Tasks:**
  - [ ] Spring Initializr con las capacidades de la [tabla del Sprint 0](sprint-0.md#2-proyecto-spring-boot-ow-002).
  - [ ] Fijar versiones: Java 25, Spring Boot 4.x, Spring Modulith, springdoc, Testcontainers.
  - [ ] Plugins: Spotless (palantir), Surefire y Failsafe, JaCoCo, Enforcer, compilador con `-Xlint:all -Werror`.
  - [ ] Resolver las verificaciones abiertas del Sprint 0 y anotarlas en los ADR correspondientes.
- **Acceptance Criteria:** `./mvnw verify` en verde. `./mvnw spotless:check` en verde. `./mvnw dependency:tree` sin dependencias no justificadas.
- **Testing:** `ApplicationStartupIT` (en OW-007).
- **Security considerations:** sin dependencias de fases futuras.
- **Dependencies:** OW-001.
- **Definition of Done:** versiones anotadas en el README y en los ADR.

### OW-003 · Declarar los módulos y verificarlos con Spring Modulith
`architecture` `testing` `phase-0`

- **Context:** los límites de módulo tienen que romper el build desde el principio ([ADR-003](../adr/ADR-003-spring-modulith.md)).
- **Objective:** siete módulos declarados con sus dependencias permitidas y verificados en CI.
- **Tasks:**
  - [ ] `package-info.java` por módulo con `allowedDependencies` y `@NullMarked`.
  - [ ] `shared` como módulo `OPEN`.
  - [ ] `ModularityTests` (`verify()` y `Documenter`).
  - [ ] Reglas ArchUnit: sin inyección por campo, sin `Instant.now()`, sin `@Transactional` en `web`.
- **Acceptance Criteria:** una dependencia `monitoring → incident` introducida a propósito en una rama rompe el build.
- **Testing:** `ModularityTests` y la prueba negativa manual.
- **Security considerations:** prepara la regla "solo `egress` construye clientes HTTP" (se activa en OW-024).
- **Dependencies:** OW-002.
- **Definition of Done:** la documentación generada por `Documenter` se publica como artefacto de CI.

### OW-004 · PostgreSQL, Flyway y Docker Compose para desarrollo local
`devops` `phase-0`

- **Context:** la aplicación necesita PostgreSQL real desde el primer día. Hibernate nunca toca el esquema.
- **Objective:** `docker compose up -d postgres` y la aplicación conectada con Flyway activo.
- **Tasks:**
  - [ ] `docker-compose.yml` con PostgreSQL 18 fijado por digest, healthcheck y puerto en loopback.
  - [ ] `application.yml`: `ddl-auto=validate`, `open-in-view=false`, `clean-disabled=true`, UTC.
  - [ ] Flyway con `db/migration`.
  - [ ] `.env.example` y `scripts/dev-keys.sh`.
- **Acceptance Criteria:** la aplicación arranca con el perfil `local` contra el Compose. Borrar el volumen y volver a arrancar funciona.
- **Testing:** `ApplicationStartupIT` con Testcontainers.
- **Security considerations:** PostgreSQL no se publica fuera de `127.0.0.1`. Sin contraseñas en los ficheros versionados.
- **Dependencies:** OW-002.
- **Definition of Done:** instrucciones de arranque en el README comprobadas desde un clon limpio.

### OW-005 · Manejo de errores base con Problem Details
`architecture` `phase-0`

- **Context:** todos los errores deben tener el mismo formato ([guía de API](../api/api-guidelines.md#8-formato-de-error-problem-details-rfc-9457)).
- **Objective:** jerarquía `DomainException` y traducción uniforme a `application/problem+json`.
- **Tasks:**
  - [ ] Excepciones base en `shared.error`.
  - [ ] `@RestControllerAdvice` para las excepciones de dominio, la validación, el JSON malformado, las propiedades desconocidas, `404`, `405` y `500`.
  - [ ] `AuthenticationEntryPoint` y `AccessDeniedHandler` con el mismo formato.
  - [ ] `PageResponse<T>`.
- **Acceptance Criteria:** cada tipo de error devuelve el `code` del catálogo y el `requestId`. Un `500` no revela ni la clase ni el mensaje de la excepción.
- **Testing:** `ProblemDetailsIT` con un controlador de prueba (solo en el perfil de test) que lanza cada excepción.
- **Security considerations:** sin stack traces ni SQL en las respuestas.
- **Dependencies:** OW-002.
- **Definition of Done:** el catálogo de códigos en la guía de API coincide con el código.

### OW-006 · Logging estructurado, request id, perfiles y salvaguardas de arranque
`devops` `security` `phase-0`

- **Context:** hace falta trazabilidad por petición y configuración segura por entorno.
- **Objective:** logs correlacionables y perfiles que fallan si están mal configurados.
- **Tasks:**
  - [ ] `RequestIdFilter` (validación del header entrante, MDC y header de respuesta).
  - [ ] JSON ECS en `staging` y `production`, texto en `local`.
  - [ ] Ficheros por perfil ([entornos](../devops/environments.md)).
  - [ ] Validador de salvaguardas de arranque con las reglas aplicables.
  - [ ] Beans `Clock` y `RandomGenerator`.
- **Acceptance Criteria:** cada línea de log de una petición lleva `requestId`. Un `X-Request-Id` con caracteres inválidos se sustituye. `production` con CORS `*` no arranca.
- **Testing:** `RequestIdFilterTest`, `ProductionGuardrailsTest` (un contexto por regla).
- **Security considerations:** el `requestId` entrante se valida (longitud y juego de caracteres) para evitar la inyección en los logs.
- **Dependencies:** OW-002.
- **Definition of Done:** catálogo de propiedades actualizado con lo implementado.

### OW-007 · Infraestructura de tests con Testcontainers
`testing` `phase-0`

- **Context:** sin H2. Los tests de integración usan PostgreSQL real y deben ser rápidos ([estrategia](../testing/testing-strategy.md#testcontainers)).
- **Objective:** un contenedor compartido, Surefire y Failsafe separados y convenciones claras.
- **Tasks:**
  - [ ] `PostgresTestcontainer` con `@ServiceConnection`.
  - [ ] Surefire (`*Test`) y Failsafe (`*IT`) en el `pom.xml`.
  - [ ] `ApplicationStartupIT`.
  - [ ] Guía breve en `docs/testing/` para reutilizar el contenedor en local.
- **Acceptance Criteria:** `./mvnw test` no arranca Docker. `./mvnw verify` sí. Un solo contenedor por ejecución.
- **Testing:** la propia suite.
- **Security considerations:** ninguna.
- **Dependencies:** OW-004.
- **Definition of Done:** el tiempo de `verify` anotado como baseline.

### OW-008 · Seguridad base: denegar por defecto, cabeceras, CORS y `SecretCipher`
`security` `phase-0`

- **Context:** la seguridad es la base, no un añadido ([arquitectura de seguridad](../security/security-architecture.md)).
- **Objective:** todo cerrado salvo `health` y la documentación en `local`. Cifrado simétrico listo para la Fase 2.
- **Tasks:**
  - [ ] `SecurityFilterChain` sin estado, con CSRF desactivado para Bearer (justificado en un comentario), sin formulario de login ni Basic.
  - [ ] Cabeceras de seguridad.
  - [ ] CORS configurable.
  - [ ] Actuator en el puerto 8081 con solo `health` e `info`.
  - [ ] `SecretCipher` AES-256-GCM con `keyId` y dato asociado.
- **Acceptance Criteria:** `GET /api/v1/x` → `401` con Problem Details. Las cabeceras de seguridad están presentes. `SecretCipher` detecta la manipulación del texto cifrado.
- **Testing:** test de las cabeceras, de `401` y `SecretCipherTest` (ida y vuelta, tag alterado, AAD distinto, `keyId` desconocido).
- **Security considerations:** nonce aleatorio de 12 bytes por cifrado. Nunca se reutiliza un nonce con la misma clave.
- **Dependencies:** OW-005.
- **Definition of Done:** la sección de cifrado de la arquitectura de seguridad refleja la implementación.

### OW-009 · Dockerfile multi-stage y endurecimiento del contenedor
`devops` `security` `phase-0`

- **Context:** imagen pequeña, no root y sin secretos ([Docker](../devops/docker.md)).
- **Objective:** imagen reproducible con healthcheck.
- **Tasks:**
  - [ ] Dockerfile multi-stage con capas.
  - [ ] `.dockerignore`.
  - [ ] Servicio `app` en Compose (`read_only`, `cap_drop`, `no-new-privileges`, límites).
  - [ ] Medir el tamaño de la imagen.
- **Acceptance Criteria:** `docker run --rm opswatch:local id` muestra el UID 10001. El contenedor llega a *healthy*. `docker history` no muestra secretos.
- **Testing:** manual y en CI (build).
- **Security considerations:** imagen base por digest. Ficheros de la aplicación propiedad de root.
- **Dependencies:** OW-004.
- **Definition of Done:** tamaño anotado en `docker.md`.

### OW-010 · Pipeline de CI inicial
`devops` `phase-0`

- **Context:** `main` debe estar siempre verde de forma verificable.
- **Objective:** CI con build, tests, formato, secretos, imagen y escaneo.
- **Tasks:**
  - [ ] `ci.yml` (jobs `build`, `secrets-scan` e `image`).
  - [ ] Acciones fijadas por SHA y `permissions` mínimos.
  - [ ] Trivy con fallo ante `CRITICAL` o `HIGH` corregibles.
  - [ ] Publicación en GHCR en `main`.
  - [ ] Checks obligatorios en la protección de rama.
- **Acceptance Criteria:** un PR mal formateado falla. Un PR con una clave falsa falla en gitleaks. Un merge a `main` publica `ghcr.io/ricardoord/opswatch:sha-…`.
- **Testing:** PR de prueba para cada caso de fallo.
- **Security considerations:** `packages: write` solo en el job `image`. Sin secretos de la aplicación en el pipeline.
- **Dependencies:** OW-003, OW-007, OW-009.
- **Definition of Done:** tiempo total del pipeline anotado.

### OW-011 · Publicar la documentación de diseño
`documentation` `phase-0`

- **Context:** la documentación de arquitectura y planificación ya está redactada.
- **Objective:** llevarla al repositorio como primer PR y abrir las issues del backlog.
- **Tasks:**
  - [ ] PR con `docs/` y el README.
  - [ ] Crear las issues OW-001 a OW-036 a partir de este documento.
  - [ ] Revisar los enlaces internos.
- **Acceptance Criteria:** todos los enlaces de `docs/` resuelven. Las issues existen con sus etiquetas.
- **Testing:** comprobador de enlaces de Markdown (manual o una acción en CI).
- **Security considerations:** la documentación no contiene secretos, IPs ni rutas del servidor.
- **Dependencies:** OW-001.
- **Definition of Done:** issues enlazadas desde este backlog.

---

## Fase 1: Identity y organizaciones

### OW-012 · Registro de usuarios
`feature` `security` `phase-1`

- **Context:** primer caso de uso real. Establece el patrón (DTO, servicio, dominio, migración, tests de API).
- **Objective:** `POST /api/v1/auth/register` según el [catálogo](../api/endpoints-v1.md#autenticación-identity).
- **Tasks:**
  - [ ] Migración `V1__identity_create_users.sql`.
  - [ ] Entidad `User` con invariantes (email normalizado, longitud del nombre).
  - [ ] `RegistrationService` con `DelegatingPasswordEncoder` (bcrypt con coste 12).
  - [ ] Validación de la contraseña (12 caracteres a 72 bytes UTF-8).
  - [ ] Generador de UUIDv7.
- **Acceptance Criteria:** `201` con el usuario sin hash. Un email duplicado (sin distinguir mayúsculas) da `409`. Una contraseña de 73 bytes da `400`.
- **Testing:** unitarios de `User` y de la validación de contraseña; `UserRepositoryIT` (índice único); `RegistrationApiIT`.
- **Security considerations:** el hash nunca sale en las respuestas ni en los logs. Riesgo de enumeración aceptado hasta la Fase 5 (T-06).
- **Dependencies:** Fase 0.
- **Definition of Done:** endpoint visible en OpenAPI con sus errores documentados.

### OW-013 · Login con access token JWT
`feature` `security` `phase-1`

- **Context:** [ADR-004](../adr/ADR-004-security-strategy.md): JWT RS256 de 15 minutos validado por Resource Server.
- **Objective:** `POST /auth/login` que emite el access token, y validación del JWT en toda la API.
- **Tasks:**
  - [ ] Dependencia de OAuth2 Resource Server.
  - [ ] `JwtEncoder` y `JwtDecoder` con claves desde la configuración (`kid`, `iss`, `aud`).
  - [ ] `AuthenticationService` con hash señuelo para emails inexistentes.
  - [ ] `CurrentUser` en `shared.security`.
  - [ ] `GET /me`.
- **Acceptance Criteria:** un login correcto da un token que sirve en `/me`. Unas credenciales erróneas dan `401 invalid-credentials`, con el mismo mensaje y un tiempo similar exista o no el email.
- **Testing:** `JwtSecurityIT`: firma alterada, `alg: none`, HS256 con la clave pública, caducado, `iss` o `aud` incorrectos → `401`.
- **Security considerations:** algoritmo fijado. El token no lleva roles.
- **Dependencies:** OW-012.
- **Definition of Done:** flujo documentado en la arquitectura de seguridad según lo implementado.

### OW-014 · Refresh token con rotación, detección de reutilización y logout
`feature` `security` `phase-1`

- **Context:** refresh opaco en una cookie `HttpOnly`, que rota y detecta la reutilización.
- **Objective:** `POST /auth/refresh` y `POST /auth/logout`. `POST /me/password` revoca todas las familias.
- **Tasks:**
  - [ ] Migración `V2__identity_create_refresh_tokens.sql`.
  - [ ] `RefreshTokenService` (emitir, rotar con `FOR UPDATE`, detectar la reutilización, revocar la familia).
  - [ ] Cookie con los atributos correctos.
  - [ ] Comprobación de `Origin` en refresh y logout.
  - [ ] Job de purga de tokens vencidos.
- **Acceptance Criteria:** refresh → token nuevo y el anterior deja de servir. Reutilizar el anterior → `401` y la familia entera revocada. Logout → la cookie borrada y la familia revocada.
- **Testing:** unitarios de la lógica de rotación; `RefreshTokenIT`, incluido el refresh concurrente con el mismo token.
- **Security considerations:** solo se guarda el hash (SHA-256). La reutilización genera un evento de seguridad.
- **Dependencies:** OW-013.
- **Definition of Done:** cubiertos T-03 y T-09 del threat model.

### OW-015 · Rate limiting de los endpoints de autenticación
`security` `phase-1`

- **Context:** protección contra fuerza bruta y credential stuffing sin bloquear cuentas.
- **Objective:** límites de la [tabla de rate limiting](../security/security-architecture.md#8-rate-limiting) con Bucket4j en memoria.
- **Tasks:**
  - [ ] Dependencia de Bucket4j.
  - [ ] `AuthRateLimiter` con claves por IP y por email.
  - [ ] Respuesta `429` con `Retry-After` y Problem Details.
  - [ ] IP real detrás del proxy de confianza (`forward-headers-strategy`).
- **Acceptance Criteria:** el undécimo login por minuto desde una IP da `429`. El sexto intento contra un mismo email desde IPs distintas da `429`.
- **Testing:** `AuthRateLimitIT` con un `Clock` controlado.
- **Security considerations:** `X-Forwarded-For` solo se acepta desde proxies de confianza. Límites conocidos con varias instancias (ADR-009).
- **Dependencies:** OW-013.
- **Definition of Done:** límites en el catálogo de propiedades.

### OW-016 · Organizaciones: crear, listar, ver, renombrar y borrar
`feature` `phase-1`

- **Context:** tenant raíz. Quien la crea queda como `OWNER`.
- **Objective:** endpoints de organizaciones del catálogo.
- **Tasks:**
  - [ ] Migración `V3__organization_create_organizations_and_memberships.sql`.
  - [ ] Entidades `Organization` y `Membership`.
  - [ ] `OrganizationService` (creación con membresía `OWNER` en la misma transacción, cuota por usuario).
  - [ ] Borrado lógico y evento `OrganizationDeleted`.
  - [ ] `ETag` e `If-Match` en `PATCH`.
- **Acceptance Criteria:** una organización nueva aparece en `GET /organizations` con `myRole: OWNER`. Un no miembro recibe `404`. La sexta organización del mismo usuario da `422 quota-exceeded`.
- **Testing:** API por rol, IDOR y `412` con un `If-Match` obsoleto.
- **Security considerations:** el listado solo devuelve las organizaciones de las que es miembro.
- **Dependencies:** OW-013.
- **Definition of Done:** eventos documentados en `events.md`.

### OW-017 · Miembros y roles con la invariante del último `OWNER`
`feature` `security` `phase-1`

- **Context:** reglas de gestión de roles del [modelo de autorización](../security/authorization-model.md#reglas-que-la-matriz-no-expresa).
- **Objective:** endpoints de miembros con todas las reglas.
- **Tasks:**
  - [ ] `MembershipService` con `SELECT … FOR UPDATE` sobre la organización.
  - [ ] Reglas `ADMIN` frente a `OWNER`, sin autopromoción, poder abandonar la organización.
  - [ ] `UserDirectory` (API pública de `identity`) para buscar por email.
  - [ ] Cuota de miembros.
- **Acceptance Criteria:** dos `OWNER` que se degradan a la vez dejan al menos uno. Un `ADMIN` no puede asignar `ADMIN` (`403`). El último `OWNER` no puede abandonar (`409`).
- **Testing:** unitarios de las reglas; test de concurrencia; API por rol.
- **Security considerations:** enumeración de emails aceptada (T-06). Solo quienes tienen `MEMBER_MANAGE_*` pueden añadir miembros.
- **Dependencies:** OW-016.
- **Definition of Done:** la matriz del modelo de autorización coincide con el código.

### OW-018 · `AccessControl` y matriz RBAC probada
`security` `testing` `phase-1`

- **Context:** la autorización por recurso es la defensa principal contra el IDOR.
- **Objective:** `AccessControl` (API pública de `organization`) y tests de la matriz completa.
- **Tasks:**
  - [ ] `Role` → `Permission` como mapa inmutable.
  - [ ] `AccessControl.require` y `requireForProject` (`404` al no miembro, `403` al miembro sin permiso).
  - [ ] Test parametrizado de la matriz a partir de una tabla de datos.
  - [ ] Infraestructura de la matriz de endpoints: tabla endpoint × rol → código, y un test de completitud.
- **Acceptance Criteria:** cambiar un permiso en el código sin actualizar la tabla de test hace fallar el build. Un endpoint nuevo sin fila en la matriz hace fallar el test de completitud.
- **Testing:** `RolePermissionsTest` y `EndpointAuthorizationMatrixIT`.
- **Security considerations:** es el control del que dependen T-10 y T-11.
- **Dependencies:** OW-017.
- **Definition of Done:** versión 0.1.0 etiquetada.

---

## Fase 2: Proyectos y monitores

### OW-019 · Proyectos (CRUD)
`feature` `phase-2`

- **Context:** agrupación de monitores dentro de una organización.
- **Objective:** endpoints de proyectos del catálogo.
- **Tasks:**
  - [ ] Migración `V4__organization_create_projects.sql` (índice único parcial y `UNIQUE (id, organization_id)`).
  - [ ] `ProjectService` con cuota y borrado lógico, y el evento `ProjectDeleted`.
  - [ ] `ProjectDirectory` como API pública.
- **Acceptance Criteria:** un nombre duplicado en la misma organización da `409`. Puede repetirse en otra organización o tras borrar el proyecto.
- **Testing:** repositorio (índice parcial), API por rol, IDOR.
- **Security considerations:** la creación se autoriza sobre la organización de la ruta, que se valida con `AccessControl`.
- **Dependencies:** OW-018.
- **Definition of Done:** `monitorCounts` se completa en OW-021.

### OW-020 · `TargetPolicy`: validación de las URL de destino
`security` `phase-2`

- **Context:** capa 1 de SSRF ([protección SSRF](../security/ssrf-protection.md#capa-1-validación-al-guardar)).
- **Objective:** módulo `egress` con `TargetPolicy` e `IpRangeClassifier`.
- **Tasks:**
  - [ ] `IpRangeClassifier` con todos los rangos IPv4 e IPv6, incluidas las IPv4 incrustadas (mapeada, NAT64, 6to4).
  - [ ] Parser estricto: esquema, `userinfo`, puerto, formas de IP, hostnames prohibidos, IDN.
  - [ ] Resolución DNS al guardar, con todas las IP clasificadas.
  - [ ] Propiedad `allowed-private-cidrs` con sus salvaguardas (nunca la metadata, prohibida en `production`).
- **Acceptance Criteria:** todos los casos de la tabla de SSRF que se deciden al guardar dan el resultado esperado.
- **Testing:** `IpRangeClassifierTest` (primera y última IP de cada rango, más una pública vecina), `TargetPolicyTest` (la tabla completa) y los tests de las salvaguardas.
- **Security considerations:** es código crítico, así que requiere una revisión explícita contra el threat model (T-20).
- **Dependencies:** Fase 0.
- **Definition of Done:** la tabla de casos del documento de SSRF está cubierta línea a línea por los tests.

### OW-021 · Monitores: CRUD, pausa, reanudación y cuotas
`feature` `phase-2`

- **Context:** configuración de qué vigilar ([modelo de dominio](../architecture/domain-model.md#monitor)).
- **Objective:** endpoints de monitores del catálogo, con `monitor_state` inicializado.
- **Tasks:**
  - [ ] Migraciones `V5__monitoring_create_monitors.sql` y la parte de `monitor_state` (o `V6` completa sin `monitor_checks`, según convenga).
  - [ ] Entidad `Monitor` con las invariantes (rangos, timeout < intervalo, umbrales).
  - [ ] `MonitorService`: crear (con jitter inicial en `next_check_at`), editar, pausar, reanudar, borrar y los eventos `MonitorPaused` y `MonitorDeleted`.
  - [ ] Listener de `ProjectDeleted` (asíncrono, con registro).
  - [ ] Filtros, ordenación con lista blanca y `monitorCounts` del proyecto.
- **Acceptance Criteria:** los del catálogo y la Fase 2 del [roadmap](roadmap.md#fase-2-proyectos-y-monitores--020).
- **Testing:** unitarios de las invariantes; API por rol; IDOR; `ProjectDeleted` → monitores borrados (`@ApplicationModuleTest`).
- **Security considerations:** URL validada con `TargetPolicy`. `projectId` solo desde la ruta.
- **Dependencies:** OW-019, OW-020, OW-022.
- **Definition of Done:** el ejemplo de CharityLink se puede crear entero por la API.

### OW-022 · Headers cifrados en los monitores
`security` `phase-2`

- **Context:** los headers pueden llevar credenciales de terceros (activo A4).
- **Objective:** headers cifrados con `SecretCipher` y de solo escritura en la API.
- **Tasks:**
  - [ ] `AttributeConverter` JPA con `SecretCipher` (AAD = id del monitor).
  - [ ] Validación: lista de headers prohibidos, CR y LF, gramática de token, 10 headers como máximo y 1024 bytes por valor.
  - [ ] Respuesta con `value: null` y `hasValue: true`.
  - [ ] `toString()` sin valores.
- **Acceptance Criteria:** en la base de datos solo aparece texto cifrado. Ninguna respuesta ni log contiene el valor.
- **Testing:** converter, API (forma de la respuesta), captura de logs.
- **Security considerations:** rotación de clave prevista con `keyId`.
- **Dependencies:** OW-008.
- **Definition of Done:** T-14 cubierto.

### OW-023 · Tests de aislamiento multi-tenant (IDOR) de la Fase 2
`testing` `security` `phase-2`

- **Context:** cada endpoint con id nuevo debe probarse entre organizaciones.
- **Objective:** ampliar la matriz de endpoints y los tests de IDOR a proyectos y monitores.
- **Tasks:**
  - [ ] Filas nuevas en la matriz de autorización.
  - [ ] Fixture de dos organizaciones con datos.
  - [ ] Comprobar que no hay efectos en la base de datos tras un `404`.
- **Acceptance Criteria:** el 100 % de los endpoints de la Fase 2 están en la matriz y en los tests de IDOR.
- **Testing:** la propia issue.
- **Security considerations:** T-10.
- **Dependencies:** OW-021.
- **Definition of Done:** versión 0.2.0 etiquetada.

---

## Fase 3: Motor de monitoreo

### OW-024 · `GuardedDnsResolver` y cliente HTTP saliente endurecido
`security` `phase-3`

- **Context:** capas 2 a 5 de SSRF. Es la defensa contra el DNS rebinding.
- **Objective:** `EgressHttpClients` que construye clientes Apache HttpClient 5 con el resolver protegido y todas las restricciones.
- **Tasks:**
  - [ ] Dependencia de Apache HttpClient 5.
  - [ ] `GuardedDnsResolver` y `BlockedTargetException`.
  - [ ] Cliente sin proxy, reintentos, redirects automáticos, cookies ni compresión, con límites de headers.
  - [ ] Regla ArchUnit: solo `egress` construye clientes HTTP.
- **Acceptance Criteria:** un nombre que resuelve a una IP pública y a una privada queda bloqueado. El rebinding simulado queda bloqueado. Las IP literales pasan por el resolver (test).
- **Testing:** resolver falso, WireMock y la regla ArchUnit.
- **Security considerations:** T-20, T-21 y T-23.
- **Dependencies:** OW-020.
- **Definition of Done:** documento de SSRF revisado según la implementación.

### OW-025 · `HttpMonitorClient` con Apache HttpClient 5
`feature` `phase-3`

- **Context:** separar observar de juzgar ([motor](../architecture/monitoring-engine.md#5-httpmonitorclient)).
- **Objective:** `ApacheHttpMonitorClient` con deadline total, redirects manuales y clasificación de fallos.
- **Tasks:**
  - [ ] `ProbeRequest` y `HttpObservation` (sealed).
  - [ ] Deadline con `cancel()` programado.
  - [ ] Redirects manuales (revalidación, cambio de método, headers solo al mismo origen, 5 saltos como máximo, bucles).
  - [ ] Mapeo de excepción a `FailureReason`.
  - [ ] Cierre de la conexión tras recibir los headers.
- **Acceptance Criteria:** los casos de WireMock de la [estrategia de testing](../testing/testing-strategy.md#integración-testcontainers) dan la observación esperada.
- **Testing:** `ApacheHttpMonitorClientIT` con WireMock (y TLS autofirmado).
- **Security considerations:** T-22, T-27 y T-28.
- **Dependencies:** OW-024.
- **Definition of Done:** tabla de clasificación del documento del motor verificada.

### OW-026 · Scheduler con `SKIP LOCKED` y dispatcher con virtual threads
`feature` `architecture` `phase-3`

- **Context:** [ADR-006](../adr/ADR-006-check-scheduling.md) y [ADR-007](../adr/ADR-007-http-client-and-concurrency.md).
- **Objective:** `CheckClaimer` y `CheckDispatcher` con semáforo, sin catch-up y con apagado ordenado.
- **Tasks:**
  - [ ] Consulta de claim (CTE con `FOR UPDATE SKIP LOCKED`).
  - [ ] Dispatcher con `Semaphore` y un executor de virtual threads.
  - [ ] `opswatch.monitoring.engine.enabled`.
  - [ ] Apagado ordenado (`SmartLifecycle`).
- **Acceptance Criteria:** los monitores vencidos se ejecutan una vez por intervalo. Con el semáforo lleno no se reclama nada.
- **Testing:** `CheckClaimerIT` (avance y ausencia de catch-up); test del dispatcher con un cliente falso.
- **Security considerations:** ninguna nueva.
- **Dependencies:** OW-025.
- **Definition of Done:** `EXPLAIN` del claim usa `ix_monitor_state_due` (test o anotación).

### OW-027 · Registro de resultados y máquina de estados del monitor
`feature` `phase-3`

- **Context:** la [tabla de transiciones](../architecture/domain-model.md#monitorstate) es el núcleo de la correctitud.
- **Objective:** `CheckEvaluator`, `StateTransition` y `CheckResultRecorder` con los eventos `MonitorWentDown` y `MonitorRecovered`.
- **Tasks:**
  - [ ] Migración de `monitor_checks` con su PK y el BRIN.
  - [ ] `CheckEvaluator` y `StateTransition` puros.
  - [ ] `CheckResultRecorder` (`FOR UPDATE`, inserción por `JdbcClient`, transición y evento).
  - [ ] Los errores internos no cuentan como check.
- **Acceptance Criteria:** 3 fallos → `DOWN` y un evento. 2 éxitos → `UP` y un evento. Un monitor pausado no cambia de estado.
- **Testing:** `StateTransitionTest` (la tabla completa), `CheckEvaluatorTest`, `CheckResultRecorderIT` y pausa con un check en vuelo.
- **Security considerations:** `error_detail` genérico, sin datos de la respuesta.
- **Dependencies:** OW-026.
- **Definition of Done:** tabla de transiciones del modelo de dominio verificada línea a línea.

### OW-028 · Consulta de checks (cursor) y estadísticas
`feature` `phase-3`

- **Context:** historial y uptime por la API.
- **Objective:** `GET /monitors/{id}/checks` y `GET /monitors/{id}/stats`.
- **Tasks:**
  - [ ] `CursorPage` con un cursor opaco validado.
  - [ ] Consulta de estadísticas (`FILTER` y `percentile_cont`).
  - [ ] Ventanas `24h`, `7d` y `30d`.
- **Acceptance Criteria:** con datos conocidos, el uptime y los percentiles coinciden con los calculados a mano. Un cursor manipulado da `400`.
- **Testing:** `MonitorStatsIT` con datos sembrados y test del cursor.
- **Security considerations:** IDOR en los dos endpoints.
- **Dependencies:** OW-027.
- **Definition of Done:** tiempo de `stats?window=30d` con 86 400 filas anotado.

### OW-029 · Job de retención
`feature` `performance` `phase-3`

- **Context:** crecimiento de `monitor_checks` ([retención](../database/data-retention.md)).
- **Objective:** purga diaria en lotes con un advisory lock.
- **Tasks:**
  - [ ] `RetentionJob` para checks, refresh tokens y checks de monitores borrados.
  - [ ] `pg_try_advisory_lock`.
  - [ ] Métricas de filas borradas y duración.
- **Acceptance Criteria:** borra solo lo anterior al límite. Dos instancias no lo ejecutan a la vez.
- **Testing:** `RetentionJobIT`.
- **Security considerations:** ninguna.
- **Dependencies:** OW-027.
- **Definition of Done:** propiedades en el catálogo.

### OW-030 · Métricas del motor con Micrometer
`devops` `performance` `phase-3`

- **Context:** sin métricas no hay evidencia para las Fases 7 a 10.
- **Objective:** métricas de la Fase 3 de [observabilidad](../devops/observability.md#métricas-propias) expuestas en `/actuator/prometheus`.
- **Tasks:**
  - [ ] Registro de Prometheus de Micrometer.
  - [ ] Contadores, histogramas con buckets explícitos y gauges con recálculo periódico.
  - [ ] Test de que ninguna métrica lleva etiquetas de alta cardinalidad.
- **Acceptance Criteria:** tras ejecutar checks, las métricas aparecen con los valores esperados.
- **Testing:** `EngineMetricsIT` y el test de cardinalidad.
- **Security considerations:** `/actuator/prometheus` solo en el puerto de management.
- **Dependencies:** OW-026, OW-027.
- **Definition of Done:** tabla de métricas de observabilidad verificada.

### OW-031 · Tests de concurrencia del motor
`testing` `phase-3`

- **Context:** la ausencia de duplicados con varias instancias es un requisito.
- **Objective:** demostrarlo contra PostgreSQL real.
- **Tasks:**
  - [ ] Dos claimers en paralelo sobre N monitores vencidos.
  - [ ] Pausa concurrente con el registro de un resultado.
- **Acceptance Criteria:** 0 duplicados en 100 ejecuciones del test.
- **Testing:** la propia issue.
- **Security considerations:** ninguna.
- **Dependencies:** OW-026, OW-027.
- **Definition of Done:** versión 0.3.0 etiquetada y primer resultado informal de carga anotado.

---

## Fase 4: Incidentes y notificaciones

### OW-032 · Incidentes: apertura y resolución automáticas
`feature` `phase-4`

- **Context:** [ciclo de vida de incidentes](../architecture/incident-lifecycle.md) y [eventos](../architecture/events.md).
- **Objective:** el listener síncrono de `incident` abre y resuelve incidentes a partir de los eventos del monitor.
- **Tasks:**
  - [ ] Migración `V7__incident_create_incidents_and_timeline.sql` (índice único parcial).
  - [ ] `MonitorEventsListener` (`@EventListener`) para `MonitorWentDown`, `MonitorRecovered`, `MonitorPaused` y `MonitorDeleted`.
  - [ ] Apertura idempotente y timeline.
  - [ ] Eventos `IncidentOpened` e `IncidentResolved`.
- **Acceptance Criteria:** una caída = un incidente. Pausar un monitor caído lo resuelve con `MONITOR_PAUSED`. Dos `MonitorWentDown` seguidos no crean dos incidentes.
- **Testing:** `@ApplicationModuleTest` con `Scenario`, restricción única (`IncidentRepositoryIT`) y duplicados.
- **Security considerations:** ninguna nueva.
- **Dependencies:** OW-027.
- **Definition of Done:** invariante "un incidente activo por monitor" probada a nivel de base de datos y de aplicación.

### OW-033 · Acknowledge, listados y timeline
`feature` `phase-4`

- **Context:** la interacción humana con los incidentes.
- **Objective:** endpoints de incidentes del catálogo.
- **Tasks:**
  - [ ] `POST /incidents/{id}/acknowledge` con nota.
  - [ ] Listado por organización con filtros.
  - [ ] Detalle con el timeline.
- **Acceptance Criteria:** acknowledge sobre `OPEN` → `ACKNOWLEDGED`. Sobre `RESOLVED` → `409`. Un `VIEWER` → `403`.
- **Testing:** API por rol, IDOR y concurrencia del acknowledge con la recuperación.
- **Security considerations:** nota limitada a 500 caracteres y escapada al mostrarse.
- **Dependencies:** OW-032.
- **Definition of Done:** filas en la matriz de autorización.

### OW-034 · Event Publication Registry de Spring Modulith
`architecture` `phase-4`

- **Context:** los listeners asíncronos necesitan persistencia para no perder eventos.
- **Objective:** registro activo con reenvío al reiniciar y limpieza de las publicaciones completadas.
- **Tasks:**
  - [ ] Dependencia del registro (JPA o JDBC).
  - [ ] Migración `V8__modulith_create_event_publication.sql` con el DDL de la versión fijada.
  - [ ] `republish-outstanding-events-on-restart`, modo de finalización y purga.
  - [ ] Métrica `opswatch_event_publications_incomplete`.
- **Acceptance Criteria:** si el contexto se mata tras el commit y antes del listener, al reiniciar el listener se ejecuta una vez.
- **Testing:** `EventPublicationRegistryIT` con reinicio del contexto.
- **Security considerations:** ninguna.
- **Dependencies:** OW-032.
- **Definition of Done:** sección de eventos actualizada con las propiedades reales.

### OW-035 · Canales de notificación (email y webhook)
`feature` `security` `phase-4`

- **Context:** a dónde avisar ([modelo de dominio](../architecture/domain-model.md#notificationchannel)).
- **Objective:** CRUD de canales con la configuración cifrada, un secreto de firma que se muestra una sola vez y el endpoint de prueba.
- **Tasks:**
  - [ ] Migración `V9__notification_create_channels_and_deliveries.sql`.
  - [ ] Configuración cifrada con `SecretCipher`.
  - [ ] Webhook: `TargetPolicy` solo con `https` y generación y rotación del secreto.
  - [ ] Endpoint de prueba con rate limit.
  - [ ] Mailpit en Compose (profile `mail`).
- **Acceptance Criteria:** un webhook `http://` da `422`. El secreto solo aparece en la respuesta de creación y en la de rotación.
- **Testing:** API por rol, IDOR, enmascarado y SSRF de los webhooks.
- **Security considerations:** T-30, T-32 y T-35.
- **Dependencies:** OW-018, OW-024.
- **Definition of Done:** filas en la matriz de autorización.

### OW-036 · Entrega de notificaciones con reintentos y firma HMAC
`feature` `phase-4`

- **Context:** efecto lateral fiable a partir de los eventos de incidentes.
- **Objective:** listener asíncrono que crea las entregas y un worker que las envía con backoff.
- **Tasks:**
  - [ ] `IncidentEventsListener` (`@ApplicationModuleListener`).
  - [ ] `DeliveryWorker` (`SKIP LOCKED`, backoff, 6 intentos).
  - [ ] `EmailSender` (Spring Mail, plantillas escapadas) y `WebhookSender` (egress, timeout de 5 s, firma).
  - [ ] `GET /notification-channels/{id}/deliveries`.
- **Acceptance Criteria:** los de la Fase 4 del [roadmap](roadmap.md#fase-4-incidentes-y-notificaciones--040).
- **Testing:** worker con SMTP falso y WireMock; firma verificable con un verificador independiente en el test; idempotencia ante eventos duplicados.
- **Security considerations:** T-31, T-33 y T-34.
- **Dependencies:** OW-034, OW-035.
- **Definition of Done:** versión 0.4.0 etiquetada y guía de verificación de firma para receptores.

---

## Fase 5: Endurecimiento (resumen, se detalla al cerrar la Fase 4)

| ID | Título | Etiquetas |
|---|---|---|
| OW-037 | Verificación de email y reset de contraseña; registro sin enumeración | `feature` `security` |
| OW-038 | Invitaciones a organizaciones (sustituyen al alta directa por email) | `feature` `security` |
| OW-039 | Rechazo de contraseñas comunes (lista local) | `security` |
| OW-040 | Audit log de acciones sensibles | `feature` `security` |
| OW-041 | Rate limiting general de la API y límite de concurrencia por host de destino | `security` `performance` |
| OW-042 | CodeQL, escaneo ZAP baseline, comparación de OpenAPI en CI y revisión completa del threat model | `security` `devops` `documentation` |
