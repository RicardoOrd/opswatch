# ADR-007: Apache HttpClient 5 bloqueante sobre virtual threads

- **Estado:** Aceptado
- **Fecha:** 2026-09-28
- **Relacionado:** [Motor de monitoreo](../architecture/monitoring-engine.md) · [Protección SSRF](../security/ssrf-protection.md) · [Plan de benchmarks](../performance/benchmark-plan.md)

## 1. ¿Qué problema existe?

El motor tiene que hacer cientos de peticiones HTTP concurrentes a destinos no confiables, medir su latencia con precisión y **no permitir nunca** que una de ellas alcance la red interna, tampoco por DNS rebinding. Hay que elegir el cliente HTTP y el modelo de concurrencia.

## 2. ¿Cuáles son los requisitos?

- **Control de la resolución DNS** para validar las IP y conectar exactamente a las validadas.
- Timeouts por fase y un deadline total cancelable.
- Poder desactivar los reintentos automáticos, los redirects automáticos, las cookies, la compresión y los proxies del entorno.
- Límites al tamaño de los headers de respuesta.
- Unos 100 a 300 checks en vuelo por instancia a la escala objetivo, con margen para los timeouts.
- Código legible y depurable. Sin imponer un modelo de programación a toda la aplicación.

## 3. ¿Qué alternativas tenemos?

**Cliente:** `RestClient` de Spring (sobre JDK, Apache o Jetty), `WebClient` (Reactor Netty), `java.net.http.HttpClient`, **Apache HttpClient 5 (API clásica)**, OkHttp.

**Concurrencia:** pool fijo de hilos de plataforma, **virtual threads con un semáforo**, reactivo (Reactor).

## 4 y 5. Ventajas y desventajas

| Opción | Ventajas | Desventajas |
|---|---|---|
| `RestClient` | API agradable e integrada en Spring | Los controles necesarios están en el cliente subyacente. Para esta tarea es una capa sin valor añadido |
| `WebClient` / Reactor Netty | Muy eficiente en I/O. Resolver de Netty configurable | Introduce el modelo reactivo por un solo componente. Trazas y depuración más difíciles. Sin necesidad demostrada |
| `java.net.http.HttpClient` | En el JDK, sin dependencias | **Sin gancho de resolución DNS.** Fijar la IP exigiría reescribir la URL con la IP, lo que rompe SNI y la verificación del hostname TLS |
| **Apache HttpClient 5** | **`DnsResolver` sustituible**, y el cliente conecta a las IP que devuelve. Control fino de timeouts, reintentos, redirects, cookies, compresión, proxy y límites de headers. Muy maduro | Dependencia externa. API más verbosa |
| OkHttp | Interfaz `Dns` sustituible, buena API | Pensado sobre todo para Android y Kotlin. Menos habitual en el ecosistema Spring de servidor |
| Pool de hilos de plataforma | Conocido | Hay que dimensionar el pool y su cola. Memoria por hilo |
| **Virtual threads + semáforo** | Código bloqueante simple. Miles de tareas baratas. Límite explícito con el semáforo | Hay que vigilar el pinning (el JDK 24 y posteriores lo eliminan en `synchronized`) y las llamadas nativas bloqueantes (DNS) |
| Reactivo | Máxima eficiencia | Complejidad sin necesidad demostrada |

## 6. ¿Qué elegimos?

- **Apache HttpClient 5 con la API clásica (bloqueante)**, construido **solo** por el módulo `egress`, con `GuardedDnsResolver`, sin proxy, sin reintentos, sin redirects automáticos, sin cookies, sin compresión, con límites de headers y sin reutilizar conexiones en los checks.
- **Virtual threads** (`Executors.newVirtualThreadPerTaskExecutor()`) con un **`Semaphore`** de `max-concurrent-checks` (200 por defecto).
- **Java 25** (LTS), con la distribución **Temurin** (fijada en OW-002: 25.0.4). Se descartaron 26 y 27 por no ser LTS, y 21 porque no incluye JEP 491. La comparación completa está en la sección de actualizaciones al final.
- `HttpMonitorClient` como interfaz del motor, con `ApacheHttpMonitorClient` como implementación.

## 7. ¿Por qué?

- Es el único candidato del ecosistema Java de servidor que da el **control de DNS que exige la defensa contra el DNS rebinding** junto con todos los demás controles, sin recurrir a trucos.
- Los virtual threads dan la concurrencia necesaria con código secuencial. El semáforo, y no el número de hilos, es el único límite que hay que dimensionar.
- Java 25 elimina el pinning por `synchronized` (JEP 491, desde el JDK 24), que era el riesgo principal de usar librerías bloqueantes en virtual threads.
- La interfaz `HttpMonitorClient` deja abierta la puerta a otra implementación sin tocar el dominio.

## 8. ¿Qué costo o complejidad introduce?

- Dependencia de Apache HttpClient 5 y su configuración detallada.
- Redirects implementados a mano (revalidación por salto, cambio de método y política de headers).
- La conexión nueva por check cuesta handshakes TLS y sockets en `TIME_WAIT`. Se mide.
- La resolución DNS del sistema es bloqueante y no cancelable. Se mide.
- Riesgo residual de pinning por código nativo o por librerías. Se vigila con JFR.

## 9. ¿Cómo comprobaremos que funciona?

- Tests de WireMock: timeouts, deadline, redirects, TLS y límites.
- Tests de SSRF con resolver falso (rebinding, IP mixtas) y el test de que las IP literales pasan por el resolver.
- Regla ArchUnit: solo `egress` construye clientes HTTP.
- Benchmarks B1 a B6: lag, CPU, memoria, checks en vuelo, eventos JFR `jdk.VirtualThreadPinned` y `TIME_WAIT` en B5.

## 10. ¿Qué tendría que pasar para reconsiderarla?

- Benchmarks con CPU o memoria por check en vuelo demasiado altas para la escala objetivo, o necesidad de mucho más de ~10 000 checks en vuelo por instancia: evaluar `WebClient` o Reactor Netty con un resolver propio, **solo** en el motor.
- Pinning o bloqueo de hilos portadores medido que afecte al lag.
- DNS lento que degrade el lag: resolver con timeout (SPI `InetAddressResolverProvider` o una librería DNS).
- Una versión futura de Apache HttpClient que deje de pasar por el `DnsResolver` en algún camino (el test lo detectaría).

## Actualizaciones

### 2026-09-28: versión de Java fijada en OW-002

| Opción | Motivo |
|---|---|
| 27 | No es LTS: 6 meses de parches. Descartada |
| 26 | No es LTS y sus parches ya terminaron. Descartada |
| **25 LTS (Temurin 25.0.4)** | **Elegida.** Un año en GA y cuatro rondas trimestrales de parches de seguridad. Es la primera LTS con JEP 491, del que depende este diseño. Soporte más largo que 21 |
| 21 LTS | La más madura, pero sin JEP 491: con 2 vCPU, unos pocos checks fijados a sus hilos congelarían el motor. Obligaría a usar un pool de hilos de plataforma |

Distribución: Temurin (OpenJDK de la Eclipse Foundation, gratuita). Se descarta Oracle JDK porque sus actualizaciones gratuitas para la 21 terminan en septiembre de 2026.

Si alguna herramienta del build no soportara Java 25, la alternativa es 21 con un pool de hilos de plataforma. OW-002 compiló y verificó sin problemas con 25.
