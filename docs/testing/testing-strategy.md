# Estrategia de testing

Estado: diseño inicial · Última revisión: 2026-09-28

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
| Cursor de paginación | Codificación, decodificación y rechazo de cursores manipulados |

### Integración (Testcontainers)

| Área | Qué se prueba |
|---|---|
| Migraciones | Arranque desde cero con `ddl-auto=validate`: esquema y entidades coinciden |
| Repositorios | Consultas propias, índices parciales únicos (dos incidentes activos → violación), FK compuestas, borrado lógico, keyset |
| Claim del scheduler | `SKIP LOCKED`, avance de `next_check_at` y ausencia de catch-up |
| Retención | Purga en lotes: borra solo lo viejo |
| `ApacheHttpMonitorClient` contra WireMock | Códigos, retrasos (timeout y deadline), redirects (mismo origen, otro origen, bucle, más de 5 saltos), headers enormes, `Location` inválido, TLS autofirmado |
| `GuardedDnsResolver` | Resolver falso: IP mixtas, rebinding, IP literales que pasan por el resolver |
| Módulos (`@ApplicationModuleTest`) | `incident` recibe `MonitorWentDown` y abre un incidente. `notification` recibe `IncidentOpened` y crea entregas. Idempotencia ante eventos duplicados |
| Event Publication Registry | Una publicación incompleta se reenvía al reiniciar el contexto |
| Entregas | Worker con backoff: fallo, reintento, `FAILED` tras 6 intentos. Firma HMAC verificable |

### API

- `MockMvcTester` (API de AssertJ para MockMvc de Spring Framework 6.2 y posteriores) contra el contexto completo con PostgreSQL de Testcontainers. Los endpoints se prueban **con la seguridad real activada**.
- Cada endpoint: caso feliz, validación (`400` con `errors[]`), `401`, `403`, `404` de otra organización, conflicto (`409` o `412`) donde aplique, y la forma exacta del JSON de respuesta, incluidos los campos que **no** deben aparecer (secretos).
- **No** se usa `@WebMvcTest` con los servicios mockeados para la mayoría de endpoints: probaría el mapeo de Spring más que el comportamiento. Se reserva para casos de serialización o validación aislados.

### Pruebas de seguridad

| Prueba | Detalle |
|---|---|
| Matriz de autorización de endpoints | Tabla de datos: endpoint × {`OWNER`, `ADMIN`, `MEMBER`, `VIEWER`, no miembro, anónimo} → código esperado. Un test parametrizado la recorre entera. Añadir un endpoint sin añadir su fila hace fallar un test de completitud |
| IDOR | Dos organizaciones con datos. Un usuario de B pide cada recurso de A por id → `404`, y ningún efecto en la base de datos |
| JWT | Firma alterada, `alg: none`, HS256 con la clave pública como secreto, token caducado, `iss` o `aud` incorrectos, token de un usuario deshabilitado |
| Refresh tokens | Rotación, reutilización (revoca la familia), cookie con los atributos correctos, `Origin` ajeno rechazado |
| Rate limiting | Superar el límite de login da `429` con `Retry-After` |
| Mass assignment | Propiedades no permitidas (`organizationId`, `role`, `id`) → `400` |
| SSRF | La tabla completa de [casos obligatorios](../security/ssrf-protection.md#5-casos-de-prueba-obligatorios) |
| Redacción | Se captura el log de un login, de la creación de un monitor con headers y de un webhook, y se comprueba que no aparecen secretos. `toString()` de los DTOs sensibles |
| Salvaguardas de producción | El contexto con el perfil `production` y configuración insegura **no arranca** |
| Errores | Un `500` provocado no devuelve stack trace ni mensajes internos |

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
| Dos dispatchers reclamando a la vez | Dos hilos con `CheckClaimer` contra el mismo PostgreSQL y N monitores vencidos. Ningún monitor reclamado dos veces por intervalo |
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
| HTTP externo | WireMock (dentro del proceso o en contenedor) |
| Redis o un broker (etapas futuras) | Sí, el mismo patrón |

**H2 nunca.** El comportamiento que más importa probar (bloqueos, índices parciales, tipos de fecha, funciones de agregación) es justo el que H2 emula mal o no emula.

### Rapidez

1. **Un contenedor de PostgreSQL por ejecución de la JVM**, compartido por todas las clases de test con `@ServiceConnection`:

```java
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestcontainer {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:18-alpine");   // misma imagen que en producción
    }
}
```

(La clase y el paquete exactos del contenedor dependen de la versión de Testcontainers fijada en el Sprint 0: la 2.x reorganizó módulos y paquetes.)

2. **Reutilización del contexto de Spring:** pocas configuraciones de contexto distintas. Cada `@MockitoBean` o perfil diferente crea un contexto nuevo, así que se usan con criterio.
3. **Aislamiento de datos sin recrear la base de datos:** cada test crea sus propios datos con ids únicos y no depende de que la base de datos esté vacía. Donde hace falta empezar de cero, `TRUNCATE` de las tablas del módulo antes del test.
4. **Reutilización del contenedor entre ejecuciones en local:** `testcontainers.reuse.enable=true` en `~/.testcontainers.properties` del desarrollador. **No en CI**, que siempre empieza limpio.
5. **Separación Surefire y Failsafe:** `*Test` son unitarios (`mvn test`, sin Docker) y `*IT` son de integración (`mvn verify`). Se puede iterar sobre lógica pura sin arrancar contenedores.
6. **Objetivo:** la suite completa por debajo de 5 minutos en CI. Si se supera, primero se revisa el número de contextos de Spring.

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
