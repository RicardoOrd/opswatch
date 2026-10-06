# Estrategia de testing

Estado: diseño inicial · Última revisión: 2026-10-05

## Objetivos

1. **Confianza para cambiar.** Refactorizar un módulo sin miedo.
2. **Probar lo que puede romperse de verdad:** reglas de dominio, seguridad, concurrencia y comportamiento contra PostgreSQL real.
3. **Feedback rápido:** unitarios en segundos y la suite completa de un PR en pocos minutos.
4. **Sin flakiness:** un test que falla a veces se arregla o se borra, no se reintenta.

## Pirámide de OpsWatch

```text
                    ┌─────────────┐
                    │  E2E smoke  │  pocos: la imagen Docker real contra PostgreSQL y un destino simulado
                  ┌─┴─────────────┴─┐
                  │ API + seguridad │  matriz de autorización, IDOR, contratos HTTP y errores
                ┌─┴─────────────────┴─┐
                │ Integración (PG real)│  repositorios, migraciones, scheduler, módulos, cliente HTTP
              ┌─┴─────────────────────┴─┐
              │        Unitarios        │  la mayoría: dominio puro, clasificador SSRF, RBAC
              └─────────────────────────┘
  Aparte: arquitectura (Modulith), concurrencia y rendimiento (fuera del pipeline de PR)
```

| Nivel | Proporción aproximada | Tiempo por test | Herramientas | Se ejecuta en |
|---|---|---|---|---|
| Unitarios | ~60 % | ms | JUnit 5, AssertJ, Mockito (con moderación) | Cada build |
| Integración | ~25 % | 50–500 ms, más el arranque compartido del contenedor | Spring Boot Test, Testcontainers, WireMock | Cada PR |
| API y seguridad | ~12 % | 20–200 ms | `MockMvcTester`, Spring Security Test | Cada PR |
| Arquitectura | 2–3 tests | s | Spring Modulith (`verify()`), ArchUnit | Cada PR |
| E2E smoke | ~5 escenarios | s | Imagen Docker, Testcontainers o Compose | PR (Fase 6+) y cada despliegue |
| Rendimiento | Escenarios de benchmark | min | k6, simulador de destinos | Manual, en la Fase 7 y antes de decisiones de arquitectura |

Las proporciones son orientativas. Lo importante es **dónde** se prueba cada cosa, no cumplir un porcentaje.

## Qué se prueba en cada nivel

### Unitarios

La lógica pura, sin Spring, sin base de datos y sin red:

| Componente | Qué se prueba |
|---|---|
| `StateTransition` | La tabla completa de transiciones del [modelo de dominio](../architecture/domain-model.md#monitorstate), incluidos los cambios de umbral en caliente y el estado `PAUSED` |
| `CheckEvaluator` | Rango esperado, umbral de degradación y cada `FailureReason` |
| `IpRangeClassifier` | Cada rango bloqueado: primera y última IP, una IP pública vecina, IPv4 mapeada, NAT64 y 6to4 |
| `TargetPolicy` (forma) | La tabla de [casos SSRF](../security/ssrf-protection.md#5-casos-de-prueba-obligatorios) que no necesita red |
| `Role` → `Permission` | La matriz completa, como datos de test parametrizados |
| Invariantes de las entidades | `Monitor` (rangos, timeout < intervalo), `Membership` (reglas de rol), `Incident` (transiciones válidas) |
| Cálculo de `next_check_at` y del jitter | Con `Clock` fijo y `RandomGenerator` inyectado |
| `SecretCipher` | Ida y vuelta, detección de manipulación (tag GCM), dato asociado de otra entidad, `keyId` desconocido |
| Refresh tokens | Rotación, reutilización y caducidad de la familia (lógica aislada del repositorio) |
| Cursor de paginación | Codificación, decodificación y rechazo de cursores manipulados (`CursorPageTest`, OW-028) |

### Integración (Testcontainers)

| Área | Qué se prueba |
|---|---|
| Migraciones | Arranque desde cero con `ddl-auto=validate`: esquema y entidades coinciden |
| Repositorios | Consultas propias, índices parciales únicos (dos incidentes activos → violación), FK compuestas, borrado lógico, keyset |
| Claim del scheduler | `SKIP LOCKED`, avance de `next_check_at`, ausencia de catch-up y plan con el índice parcial (`CheckClaimerIT`). Los tests del motor reclaman en 2001 (`PastSchedule`): la base es compartida y los demás tests programan sus monitores a la hora real, así que solo ven los suyos. Antes y después de cada test se desprograma todo lo anterior a 2002 |
| Dispatcher | Con un `HttpMonitorClient` falso y lo demás real: permisos, errores propios y apagado (`CheckDispatcherIT`). `MonitoringEngineIT` activa el motor en su propio contexto, cerrado con `@DirtiesContext`: mientras está abierto comprueba todos los monitores vencidos de la base compartida |
| Retención | Purga en lotes: borra solo lo viejo |
| `ApacheHttpMonitorClient` contra WireMock | Códigos, retrasos (timeout y deadline), redirects (mismo origen, otro origen, bucle, más de 5 saltos), headers enormes, `Location` inválido, TLS autofirmado |
| `GuardedDnsResolver` | Resolver falso: IP mixtas, rebinding, IP literales que pasan por el resolver |
| Módulos (`@ApplicationModuleTest`) | `incident` recibe `MonitorWentDown` y abre un incidente. `notification` recibe `IncidentOpened` y crea entregas. Idempotencia ante eventos duplicados |
| Event Publication Registry | Una publicación incompleta se reenvía al reiniciar el contexto |
| Entregas | Worker con backoff contra GreenMail (`com.icegreen:greenmail-junit5`), que se para dentro del test para simular el SMTP caído: fallo, reintento, `SENT` al volver y `FAILED` tras 6 intentos. Webhooks contra WireMock: firma HMAC verificable y `3xx` como intento fallido |

### API

- `MockMvcTester` (API de AssertJ para MockMvc de Spring Framework 6.2 y posteriores) contra el contexto completo con PostgreSQL de Testcontainers. Los endpoints se prueban **con la seguridad real activada**.
- Cada endpoint: caso feliz, validación (`400` con `errors[]`), `401`, `403`, `404` de otra organización, conflicto (`409` o `412`) donde aplique, y la forma exacta del JSON de respuesta, incluidos los campos que **no** deben aparecer (secretos).
- **No** se usa `@WebMvcTest` con los servicios mockeados para la mayoría de endpoints: probaría el mapeo de Spring más que el comportamiento. Se reserva para casos de serialización o validación aislados.
- MockMvc no pasa por Tomcat. Lo que depende del servidor real, como la IP del cliente que resuelve el `RemoteIpValve` a partir de `X-Forwarded-For`, se prueba con `RANDOM_PORT` y el `HttpClient` del JDK (`AuthRateLimitIT`). Todas las peticiones de MockMvc llegan desde 127.0.0.1: por eso el perfil `test` sube los límites de rate limiting, y `AuthRateLimitIT` prueba los reales en su propio contexto.

### Pruebas de seguridad

| Prueba | Detalle |
|---|---|
| Matriz de autorización de endpoints | Tabla de datos: endpoint × {`OWNER`, `ADMIN`, `MEMBER`, `VIEWER`, no miembro, anónimo} → código esperado. Un test parametrizado la recorre entera. Añadir un endpoint sin añadir su fila hace fallar un test de completitud |
| IDOR | Dos organizaciones con datos. Un usuario de B pide cada recurso de A por id → `404`, y ningún efecto en la base de datos |
| JWT | Firma alterada, `alg: none`, HS256 con la clave pública como secreto, token caducado, `iss` o `aud` incorrectos, `kid` desconocido. Un usuario deshabilitado no obtiene tokens nuevos (login y refresh → `401`); el access token que ya tenía vale hasta caducar ([ADR-004](../adr/ADR-004-security-strategy.md)) |
| Refresh tokens | Rotación, reutilización (revoca la familia), cookie con los atributos correctos, `Origin` ajeno rechazado |
| Rate limiting | Superar el límite de login da `429` con `Retry-After` |
| Mass assignment | Propiedades no permitidas (`organizationId`, `role`, `id`) → `400` |
| SSRF | La tabla completa de [casos obligatorios](../security/ssrf-protection.md#5-casos-de-prueba-obligatorios) |
| Redacción | Se captura el log de un login, de la creación de un monitor con headers y de un webhook, y se comprueba que no aparecen secretos. `toString()` de los DTOs sensibles |
| Salvaguardas de producción | El contexto con el perfil `production` y configuración insegura **no arranca** |
| Errores | Un `500` provocado no devuelve stack trace ni mensajes internos |

#### Cómo añadir un endpoint a la matriz de autorización

La matriz vive en `EndpointAuthorizationMatrixIT` (paquete raíz de los tests) y tiene dos partes:

1. **`MATRIX`**: una fila por endpoint con el método, la ruta tal como la registra Spring MVC (`/api/v1/organizations/{orgId}`) y el código que recibe cada llamante, en este orden: `OWNER`, `ADMIN`, `MEMBER` y `VIEWER` de la organización de la ruta, un usuario autenticado que no es miembro y un anónimo. Un endpoint sin organización en la ruta responde igual a cualquier usuario autenticado.
2. **`requests()`**: una petición **válida** al endpoint, construida con el `Fixture` del caso. Así el código solo depende de quién llama. Un endpoint que cambia o borra un miembro actúa sobre `fixture.subject()`, un `MEMBER`; uno que añade usa `fixture.newcomerEmail()`.

Cada caso crea su propia organización con un miembro de cada rol, por SQL y con los tokens emitidos directamente. Así ningún caso depende de otro, aunque borre la organización. Los recursos de fases siguientes (proyectos, monitores) necesitarán ampliar el `Fixture` con uno de cada.

Dos tests lo mantienen al día:
- `everyEndpointOfTheApiHasItsRow` compara las filas y las peticiones con todos los endpoints registrados bajo `/api/`. Un endpoint nuevo sin fila, una fila de un endpoint que ya no existe o una ruta que no declara su método HTTP rompen el build;
- `answersEachCallerAsTheMatrixSays` recorre cada fila con cada llamante. Un cambio de permisos que la tabla no refleje rompe el build.

La matriz prueba **quién** puede llamar. Las reglas que dependen del estado (el último `OWNER`, la cuota, `If-Match`) y el IDOR con datos de dos organizaciones siguen en los tests de API de cada recurso.

### Arquitectura

```java
@Test
void verifiesModularStructure() {
    ApplicationModules.of(OpsWatchApplication.class).verify();
}
```

Reglas ArchUnit adicionales, solo las que Modulith no cubre y que tienen valor:

- Los controladores (`..web..`) no devuelven tipos anotados con `@Entity`.
- `@Transactional` no aparece en las clases de `..web..`.
- No se usa inyección por campo (`@Autowired` en campos).
- El código de producción no usa `Instant.now()`, `LocalDateTime.now()` ni `System.currentTimeMillis()`: se usa el `Clock` inyectado.
- Solo el módulo `egress` construye clientes HTTP salientes. Ninguna otra clase instancia `HttpClients`, `RestClient.builder()` ni `java.net.http.HttpClient`.

La última regla convierte una decisión de seguridad (todo el tráfico saliente pasa por `egress`) en algo que el build comprueba.

### Concurrencia

| Escenario | Cómo |
|---|---|
| Varias instancias reclamando a la vez | `CheckClaimerConcurrencyIT`: cuatro hilos con `CheckClaimer` contra el mismo PostgreSQL, 1 000 monitores vencidos y 20 rondas. Cada monitor reclamado exactamente una vez por ronda |
| Dos `OWNER` que se degradan a la vez | Dos transacciones sincronizadas con un `CountDownLatch`. Queda al menos un `OWNER` |
| Pausa con un check en vuelo | Se reclama, se pausa y se registra el resultado: el estado sigue en `PAUSED` |
| Acknowledge y recuperación a la vez | Una de las dos falla con conflicto y el estado final es coherente |
| Dos `MonitorWentDown` para el mismo monitor | Un solo incidente activo |
| Refresh concurrente con el mismo token | Uno rota y el otro detecta la reutilización |

Estos tests usan PostgreSQL real, porque los bloqueos son exactamente lo que se prueba. Esperan con Awaitility o latches, nunca con `Thread.sleep`.

### E2E smoke

Desde la Fase 6, con la imagen Docker construida en el pipeline:

1. Arrancar `opswatch`, `postgres` y un destino simulado (WireMock) en una red de Docker, con el destino permitido por `allowed-private-cidrs` en el perfil de pruebas.
2. Registrar un usuario, crear una organización, un proyecto y un monitor contra el destino.
3. Esperar a un check `UP`.
4. Cambiar el destino a `500` y esperar a un incidente `OPEN`.
5. Volver a `200` y esperar al incidente `RESOLVED`.
6. `/actuator/health/readiness` en `UP`.

Contra staging tras cada despliegue se ejecuta una versión reducida (salud, login y lectura) que no crea datos de prueba permanentes.

### Rendimiento

Fuera del pipeline de PR. Procedimiento en el [plan de benchmarks](../performance/benchmark-plan.md).

## Qué no vale la pena probar

- Getters, setters, constructores triviales y `record`s sin lógica.
- Que Spring Data genere bien una consulta derivada simple (`findByEmail`): lo cubren los tests de API.
- Mapeos DTO ↔ entidad triviales por separado: los cubren los tests de API que comprueban el JSON.
- Configuración de Spring declarativa sin lógica.
- Métodos privados por separado: se prueban a través de su API.
- Librerías de terceros: no se prueba que bcrypt hashea, sino que el registro guarda un hash y no la contraseña.
- Un 100 % de cobertura como objetivo.

## Testcontainers

### Cuándo usar contenedores

| Situación | ¿Contenedor? |
|---|---|
| Algo depende de un comportamiento de PostgreSQL (índices parciales, `SKIP LOCKED`, BRIN, `percentile_cont`, `timestamptz`, FK compuestas) | **Sí**, PostgreSQL real |
| Lógica pura | No |
| HTTP externo | WireMock (`org.wiremock:wiremock-standalone` 3.13.2, sombreado, solo en test; decisión de Ricardo del 2026-10-03), dentro del proceso o en contenedor. Escucha en `127.0.0.1`, así que esos tests abren `opswatch.egress.allowed-private-cidrs=127.0.0.0/8`. El motor está desactivado en el perfil `test` y solo lo activan sus propios tests (OW-026). `ApplicationStartupIT`, que arranca sin el perfil, lo desactiva por propiedad: un contexto con el motor activo sigue comprobando la base compartida mientras está en la caché (OW-030) |
| Redis o un broker (etapas futuras) | Sí, el mismo patrón |

**H2 nunca.** El comportamiento que más importa probar (bloqueos, índices parciales, tipos de fecha, funciones de agregación) es justo el que H2 emula mal o no emula.

### Rapidez

1. **Un contenedor de PostgreSQL por ejecución de la JVM**, compartido por todas las clases de test. Un test de integración lo usa con `@Import(PostgresTestcontainer.class)`, y `@ServiceConnection` conecta el `DataSource` y Flyway sin propiedades:

```java
@SpringBootTest
@Import(PostgresTestcontainer.class)
class MonitorRepositoryIT { … }
```

`PostgresTestcontainer` (en `src/test`, OW-007) resuelve tres detalles que un `@Bean` que devuelve `new PostgreSQLContainer(…)` no cubre:

- **Un contenedor por JVM, no por contexto.** El contenedor es estático y el `@Bean` siempre devuelve el mismo. Un `@Bean` que lo crea tendría un contenedor por cada contexto de Spring distinto.
- **Sobrevive al cierre de cualquier contexto.** Spring Boot cierra los contenedores que son beans al cerrar su contexto: con `@DirtiesContext` o cuando la cache de contextos expulsa uno. Por eso `stop()` no hace nada y Ryuk borra el contenedor al terminar la JVM. `PostgresTestcontainerIT` lo comprueba con dos contextos distintos.
- **La imagen sale de `docker-compose.yml`.** Es la única fuente del digest y la que actualiza Dependabot, así que los tests usan el mismo PostgreSQL que el desarrollo local y producción. Testcontainers 2.0.5 no acepta `nombre:tag@digest`, así que el tag se quita y el digest fija la imagen.

2. **Reutilización del contexto de Spring:** pocas configuraciones de contexto distintas. Cada `@MockitoBean` o perfil diferente crea un contexto nuevo, así que se usan con criterio. Los tests de integración y de API usan `@IntegrationTest` (OW-012): aplicación completa, PostgreSQL, perfil `test` (`src/test/resources/application-test.yml`, con bcrypt de coste 4), un par de claves JWT generado al arrancar (`TestJwtKeys`, OW-013), una clave AES-256 por JVM para `SecretCipher` (`TestEncryptionKeys`, OW-022; también la importan `ApplicationStartupIT` y `AuthRateLimitIT`, que tienen contexto propio), un DNS falso en lugar del del sistema (`TestHostResolver`, OW-021: `api.example.com` resuelve a una IP pública e `internal.example.com` a una privada; un test que necesite otro nombre lo añade al `FakeHostResolver` compartido con un nombre único, y puede comprobar qué se resolvió) y `MockMvcTester` sobre la seguridad real. Todas las clases que la usan comparten un solo contexto.
3. **Aislamiento de datos sin recrear la base de datos:** cada test crea sus propios datos con ids únicos y no depende de que la base de datos esté vacía. Donde hace falta empezar de cero, `TRUNCATE` de las tablas del módulo antes del test.
4. **Reutilización del contenedor entre ejecuciones en local** (opcional, ahorra unos 4 s por ejecución): añadir `testcontainers.reuse.enable=true` a `~/.testcontainers.properties`. `PostgresTestcontainer` ya declara `withReuse(true)`, que solo tiene efecto con esa línea. **No en CI**, que siempre empieza limpio. Con la reutilización activa:
   - El contenedor queda en marcha al terminar (`docker ps`), con los datos y las migraciones de la ejecución anterior. Por eso los tests no pueden depender de una base de datos vacía (punto 3).
   - Editar una migración ya aplicada, algo que solo pasa mientras se escribe, hace fallar la validación de Flyway por el checksum. Se borra el contenedor (`docker rm -f <id>`) y la siguiente ejecución crea uno nuevo.
5. **Separación Surefire y Failsafe:** `*Test` y `*Tests` son unitarios (`./mvnw test`, sin Docker) y `*IT` son de integración (`./mvnw verify`). Se puede iterar sobre lógica pura sin arrancar contenedores.
6. **Objetivo:** la suite completa por debajo de 5 minutos en CI. Si se supera, primero se revisa el número de contextos de Spring.

**Baseline (OW-007, 2026-09-28):** `./mvnw clean verify` tarda **28 s** en local, con Windows 11, 12 hilos, 16 GB, Docker Desktop 29.6, la imagen ya descargada y sin reutilización. Son 56 tests unitarios y 8 de integración, con un solo contenedor que arranca en unos 4 s. En CI (OW-010), el job `build` (formato más `verify`) tarda 39 s con la cache de Maven y 1 min 36 s sin ella.

### CI

- Los runners `ubuntu-latest` de GitHub Actions tienen Docker, así que Testcontainers funciona sin configuración extra.
- Las imágenes se descargan una vez por job. Si la descarga se convierte en cuello de botella, se usa la cache de imágenes o un mirror.
- Los informes de Surefire y Failsafe se publican como artefacto cuando algo falla.

## Convenciones

- Nombres: `MonitorStateTransitionTest`, `MonitorRepositoryIT`, `MonitorApiIT`, `IncidentModuleIT`.
- Métodos que describen el comportamiento: `opensIncidentAfterThreeConsecutiveFailures()`.
- Estructura given / when / then con líneas en blanco, sin comentarios obligatorios.
- **Tiempo:** `Clock` inyectado. Nunca `Thread.sleep` para esperar un comportamiento: Awaitility con timeout.
- **Aleatoriedad:** `RandomGenerator` inyectado para el jitter, fijo en los tests.
- **Datos:** builders de test por módulo (`aMonitor().withInterval(30).build()`), no fixtures SQL compartidos.
- **Mockito:** solo en las fronteras (el `HttpMonitorClient` en los tests del motor, `EmailSender`). No se mockean repositorios en los tests de servicios que tienen lógica de persistencia: esos van con PostgreSQL real.

## Cobertura

- JaCoCo genera el informe en cada build y se publica como artefacto.
- **No hay un umbral global que bloquee.** Un porcentaje global invita a tests de relleno.
- Se revisa en cada PR que el código nuevo de `domain` y de `egress` esté cubierto. Si hace falta, más adelante se añade un umbral solo para esos paquetes.
- Mutation testing (PIT) sobre `domain` y `egress`: opcional desde la Fase 5, como medida de la calidad de los tests.

## Política ante tests inestables

1. Un test que falla de forma intermitente se marca como tal en un issue en el mismo día.
2. Se investiga la causa. Las habituales son el tiempo real, el orden entre tests, datos compartidos y esperas con `sleep`.
3. No se añaden reintentos automáticos de tests en CI.
