# Decisiones abiertas

Estado: diseño inicial · Última revisión: 2026-09-28

Estas decisiones se dejan abiertas **a propósito**. Tomarlas hoy sería adivinar. Para cada una se indica la hipótesis actual, qué evidencia la cerraría y en qué momento hay que decidir.

Cuando una se cierra, se escribe su ADR y se marca aquí como decidida con enlace.

| Decisión | Hipótesis actual | Evidencia que la cierra | Cuándo decidir | ADR |
|---|---|---|---|---|
| **Kafka o RabbitMQ** | RabbitMQ bastaría para el fan-out y las colas de trabajo con la carga prevista. Kafka compensa si Analytics necesita replay del histórico de checks | Número de consumidores, necesidad de replay, eventos por segundo medidos (`MonitorCheckCompleted`) y costo operativo en el VPS | Al abrir la Fase 11, o en la Fase 10 si ya hay dos o más consumidores entre procesos | [ADR-010](../adr/ADR-010-event-broker.md) |
| **Cliente bloqueante con virtual threads o WebClient a gran escala** | Los virtual threads alcanzan para 10 000 monitores | Benchmarks de la Fase 7: CPU, memoria y lag a 10 000 monitores a 30 s. Pinning detectado con JFR | Tras los benchmarks base (Fase 7) | [ADR-007](../adr/ADR-007-http-client-and-concurrency.md) |
| **Redis** | No hace falta mientras haya una instancia | Más de una instancia de la aplicación, o latencias de lectura atribuibles a la base de datos que la cache resolvería | Fase 9, o cuando se despliegue una segunda instancia | [ADR-009](../adr/ADR-009-redis.md) |
| **Kubernetes** | No hace falta: Docker Compose en uno o dos hosts | Necesidad de orquestar más de dos hosts, autoscaling real o despliegues rolling que Compose no dé bien | Fase 10 o posterior. Alternativas intermedias: Docker Swarm, Kamal, k3s en un solo nodo | — |
| **API Gateway concreto** | Caddy enrutando por ruta basta mientras el gateway no tenga lógica | Necesidad de autenticación, rate limiting o agregación en el borde con varios servicios | Fase 10, al extraer el primer servicio. Candidatos: Caddy o nginx, Spring Cloud Gateway (en Java, coherente con el stack), Traefik | ADR nuevo |
| **Proveedor cloud / hosting** | Un VPS barato con Docker Compose (Oracle Cloud Always Free, Hetzner u otro) | Precio, disponibilidad de ARM o x86, región y facilidad de copias de seguridad | Fase 6 | ADR nuevo |
| **Base de datos de series temporales** | No hace falta: PostgreSQL con retención y, si se necesita, particionado y rollups | `monitor_checks` crece por encima de lo que el particionado y los rollups manejan, o las consultas analíticas sobre el histórico superan el presupuesto de latencia | Fase 9 o 12. Candidatos: TimescaleDB (extensión de PostgreSQL, menor salto) o ClickHouse (analytics) | [ADR-008](../adr/ADR-008-check-results-storage.md) |
| **Elasticsearch / OpenSearch** | No hace falta: logs en JSON con `docker logs` y, más adelante, Loki; búsqueda de negocio en PostgreSQL | Volumen de logs inmanejable sin índice, o búsqueda de texto completo como funcionalidad de producto | Sin fecha | — |
| **Service mesh** | No hace falta | Más de 5 servicios con requisitos de mTLS, reintentos y observabilidad que no se resuelvan en las librerías | Sin fecha, probablemente nunca en este proyecto | — |
| **SSE o WebSocket** | SSE probablemente basta: el tiempo real es solo servidor → cliente | Requisitos bidireccionales, o limitaciones de SSE con la autenticación o los proxies | Fase 8 | [ADR-013](../adr/ADR-013-realtime-transport.md) |
| **Frontend** | Fuera de V1. La API se usa desde Swagger UI y curl | Necesidad de demo visual para el portafolio y las páginas de estado | Fase 8 | ADR nuevo |
| **Proveedor de email en producción** | SMTP de un plan gratuito (Brevo, Resend, Amazon SES u otro). Mailpit en local | Límites del plan gratuito y entregabilidad | Fase 4 (implementación) y Fase 6 (producción) | — |
| **Gestor de secretos** | Docker secrets en ficheros montados más `configtree` de Spring. Sin gestor externo | Varios hosts o servicios que necesiten rotación centralizada | Fase 10 o posterior. Candidatos: el vault del proveedor cloud, HashiCorp Vault, Infisical | — |
| **Proveedor de identidad externo / SSO / login social** | JWT propios con Spring Security | Necesidad de SSO, MFA o login con GitHub o Google | Después de V1 | [ADR-004](../adr/ADR-004-security-strategy.md) |
| **Checks desde varias regiones** | Una región | Falsos positivos por problemas de red locales, o funcionalidad pedida | Después de V1. Encaja con la extracción de Monitoring (workers por región) | — |
| **Licencia del repositorio** | Por definir (MIT o Apache-2.0 son las candidatas habituales). El repositorio es público sin licencia, lo que por defecto significa "todos los derechos reservados" | — | Antes de aceptar contribuciones externas o de que alguien quiera reutilizar el código | — |

## Cómo se cierra una decisión

1. Se reúne la evidencia indicada. Si es de rendimiento, con el procedimiento del [plan de benchmarks](../performance/benchmark-plan.md).
2. Se escribe o actualiza el ADR con las 10 preguntas de la [plantilla](../adr/README.md#plantilla).
3. Se actualiza esta tabla y los documentos afectados en el mismo pull request.
