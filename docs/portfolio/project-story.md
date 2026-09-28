# Cómo contar OpsWatch en una entrevista

Estado: borrador · Última revisión: 2026-09-28

> **Regla de este documento:** solo se cuenta lo que existe y se ha medido. Las secciones de métricas, problemas y aprendizajes tienen marcadores `[pendiente]` que se rellenan con datos reales a medida que avanza el proyecto. Una cifra inventada en una entrevista se descubre con una sola pregunta de seguimiento.

## El problema, en 30 segundos

> "OpsWatch es una plataforma SaaS multi-tenant de monitoreo de disponibilidad: comprueba periódicamente endpoints HTTP, mide su latencia, detecta caídas y recuperaciones, abre incidentes y avisa por email o webhook. La construí en Java con Spring Boot y PostgreSQL. Lo interesante no es la lista de tecnologías, sino cómo decidí cuáles usar y cuándo."

## Versión de 2 minutos

1. **Arquitectura inicial.** "Empecé con un monolito modular: una aplicación Spring Boot, siete módulos de dominio y PostgreSQL. No existía ninguna necesidad operativa de microservicios. Los límites de módulo los verifica Spring Modulith en cada build, así que no se erosionan."
2. **La pieza difícil.** "El motor de checks: programación con `SELECT … FOR UPDATE SKIP LOCKED` sobre PostgreSQL, que da varias instancias sin duplicados y sin Redis; virtual threads con un semáforo como único límite de concurrencia; y Apache HttpClient con un resolver DNS propio, porque es la única forma limpia que encontré de impedir SSRF con DNS rebinding."
3. **Correctitud.** "Un monitor caído tiene exactamente un incidente activo. Lo garantizan una transacción local y un índice único parcial en PostgreSQL. Las notificaciones van por eventos asíncronos con un outbox transaccional, para que ni se pierdan ni se envíen por algo que no se confirmó."
4. **Evolución.** "Instrumenté el motor desde el principio y diseñé benchmarks de 100 a 10 000 monitores. `[pendiente: resultado real]`. Con esos datos decidí `[pendiente: separar Monitoring / no separarlo]`."

## Decisiones importantes (y cómo defenderlas)

| Decisión | Frase de defensa | ADR |
|---|---|---|
| Monolito modular | "No había ningún módulo que necesitara escalar o desplegarse aparte. Un microservicio me habría dado red, consistencia eventual y N pipelines a cambio de nada." | 001 |
| PostgreSQL | "Necesitaba índices parciales para expresar una invariante, `SKIP LOCKED` como cola y percentiles en SQL. Y tiene un camino de crecimiento para la serie temporal sin cambiar de motor." | 002 |
| Scheduling con `SKIP LOCKED` | "La base de datos ya era la fuente de verdad. Usarla como cola me dio varias instancias sin coordinación extra, y un motivo menos para meter Redis." | 006 |
| Apache HttpClient + virtual threads | "El cliente del JDK no me deja controlar la resolución DNS, y sin eso no puedo fijar la IP validada. Los virtual threads me dan concurrencia con código bloqueante normal; en Java 25 ya no hay pinning por `synchronized`." | 007 |
| Eventos síncronos para incidentes y asíncronos para notificaciones | "Cada reacción tiene la garantía que necesita: la invariante en la misma transacción y el efecto externo después del commit con outbox. Sé que el modo síncrono no sobrevive a una extracción, y lo tengo documentado como costo." | 005 |
| Sin roles en el JWT | "Así el problema de revocación de los JWT se queda en la autenticación (15 minutos como máximo) y no afecta a la autorización, que es inmediata." | 004 |
| Sin resolución manual de incidentes | "Si dejo cerrar a mano un incidente con el monitor caído, la siguiente caída real no abre incidente, porque el monitor ya está en `DOWN`. Para silenciarlo, se pausa el monitor." | [ciclo de vida](../architecture/incident-lifecycle.md) |

## Seguridad: los tres temas que conviene sacar

1. **SSRF.** El producto hace peticiones a URLs de usuarios. Hay que explicar las capas: validación al guardar, **resolución DNS con fijación de IP al conectar** (contra el rebinding), revalidación de cada redirect, restricciones de métodos y headers (incluidos los de metadata cloud), límites en la respuesta, firewall de salida y detección. Mostrar la [tabla de casos](../security/ssrf-protection.md#5-casos-de-prueba-obligatorios) que son tests.
2. **Aislamiento multi-tenant.** Roles por membresía, tenant resuelto desde el recurso, `404` para quien no es miembro, FK compuestas y matriz de autorización generada como test.
3. **Tokens.** Access corto, refresh opaco que rota con detección de reutilización y hash de las credenciales. Reconocer el compromiso frente a BFF.

## Testing

- "Sin H2: todo lo que depende de PostgreSQL se prueba contra PostgreSQL real con Testcontainers, porque lo que más me importaba probar (bloqueos, índices parciales, percentiles) es justo lo que H2 no emula."
- "La matriz de autorización endpoint × rol es un test. Si añado un endpoint y no lo meto en la matriz, el build falla."
- "Tengo tests de concurrencia reales: dos schedulers contra la misma base de datos, dos `OWNER` que se degradan a la vez."
- Números: `[pendiente: número de tests, tiempo de la suite]`.

## Métricas

`[pendiente: se rellena con los resultados de docs/performance/results/]`

| Escenario | Checks/s | Lag p95 | CPU | Memoria | Observación |
|---|---|---|---|---|---|
| 1 000 monitores a 60 s | `[pendiente]` | `[pendiente]` | `[pendiente]` | `[pendiente]` | |
| 10 000 monitores a 60 s | `[pendiente]` | `[pendiente]` | `[pendiente]` | `[pendiente]` | |
| Interferencia con la API (B7 frente a B8) | — | — | — | — | p95 de la API: `[pendiente]` |

## Evolución: la historia central

La frase objetivo, **solo si los datos la respaldan**:

> "Empecé con un monolito modular porque no existía necesidad operacional de microservicios. Separé Monitoring cuando los benchmarks demostraron que necesitaba escalar de forma independiente: `[pendiente: criterio C1–C4 cumplido y cifra]`."

La frase alternativa, **igual de valiosa**, si los datos no lo respaldan:

> "Diseñé la extracción de Monitoring con criterios medibles. Al ejecutar los benchmarks, con separación por rol una instancia sostenía `[pendiente]` monitores con el lag p95 por debajo de `[pendiente]`, así que la extracción no se justificaba a esa escala y no la hice. Tengo el plan listo y sé qué métrica me diría que ha llegado el momento."

## Trade-offs que conviene reconocer sin que los pregunten

- Una conexión nueva por check: mediciones comparables a cambio de más handshakes TLS y sockets. `[pendiente: impacto medido]`.
- Listener síncrono monitor → incidente: correcto hoy y costoso en una extracción.
- Rate limiting en memoria: correcto con una instancia e incorrecto con varias (disparador de Redis).
- bcrypt frente a Argon2id: sin dependencias a cambio de no usar la primera opción de OWASP, con migración preparada.
- Access token en memoria y refresh en cookie frente a BFF.

## Escalabilidad: preguntas probables

| Pregunta | Respuesta corta |
|---|---|
| ¿Cómo escalas el motor? | Más instancias con `SKIP LOCKED`. Después, separación por rol (API y worker) con la misma imagen. Después, extracción si C1–C4 |
| ¿Qué pasa si la base de datos es el cuello de botella? | Inserciones en lote, transacciones más cortas, particionado de `monitor_checks`, rollups. Después, base de datos propia para Monitoring |
| ¿Por qué no Kafka? | Porque no tengo varios consumidores entre procesos ni necesidad de replay. Si llegan, comparo Kafka y RabbitMQ con un prototipo (ADR-010) |
| ¿Por qué no Redis? | Los locks los resuelve PostgreSQL. La cache y el rate limiting caben en memoria con una instancia. Tengo los disparadores escritos (ADR-009) |
| ¿Qué pasa si un destino tarda 30 s? | Ocupa un permiso del semáforo hasta el deadline. El lag lo refleja. Sin catch-up, el sistema se degrada con suavidad |
| ¿Cómo evitas incidentes duplicados? | Transacción local más un índice único parcial. Si lo distribuyera: `transitionSeq` por monitor e idempotencia |

## Problemas encontrados

`[pendiente: se registra aquí cada problema real durante el desarrollo, con qué pasó, cómo se detectó, cómo se resolvió y qué se aprendió]`

| Fecha | Problema | Detección | Solución | Aprendizaje |
|---|---|---|---|---|
| | | | | |

## Aprendizajes

`[pendiente: al cerrar cada fase]`

## Lo que no hay que decir

- Que algo está en producción o escala a N si no está medido.
- "Microservicios" si Monitoring no se extrajo.
- "Kafka", "Redis" o "Kubernetes" como experiencia del proyecto si no se adoptaron. Sí se puede explicar **por qué no** se adoptaron, y eso suele impresionar más.
- Cifras redondeadas a favor.

## Demo sugerida (5 minutos)

1. `docker compose up` y Swagger UI: registro, organización, proyecto y monitor.
2. Destino simulado sano y check `UP`. Se tumba el destino: 3 checks, `DOWN`, incidente abierto y email en Mailpit (o webhook firmado).
3. Intento de SSRF: un monitor contra `http://169.254.169.254/` da `422`, y un redirect hacia la metadata da `TARGET_BLOCKED`.
4. Otra organización pide el monitor por id: `404`.
5. Dashboard de Grafana del motor: lag, throughput, checks en vuelo.
