# ADR-012: Extracción de Notifications y Analytics

- **Estado:** Propuesto. Se decide en la Fase 12, solo después de la Etapa 4
- **Fecha:** 2026-09-28
- **Relacionado:** [Evolución, Etapa 5](../architecture/evolution.md#etapa-5-servicios-adicionales) · [ADR-010](ADR-010-event-broker.md) · [ADR-011](ADR-011-monitoring-extraction.md)

Se tratan en un solo ADR porque sus criterios son del mismo tipo (consumidores de eventos con carga o dependencias externas propias) y porque ninguno de los dos debería extraerse antes que Monitoring.

## 1. ¿Qué problema existe?

**Hipotético.**

- **Notifications:** la entrega externa (SMTP, webhooks y, en el futuro, otros canales) podría necesitar escalar, aislarse de los límites de tasa de terceros o desplegarse con otro ritmo.
- **Analytics:** los cálculos de SLA, los informes y los agregados largos podrían no caber bien en PostgreSQL transaccional ni en el ciclo de vida de Core.

## 2. ¿Cuáles son los requisitos?

- Datos que lo justifiquen, como en ADR-011.
- Un broker disponible ([ADR-010](ADR-010-event-broker.md)): los dos son consumidores naturales de eventos.
- Todo en Java y Spring Boot.

## 3. ¿Qué alternativas tenemos?

**Notifications:** seguir como módulo de Core (con su worker y su tabla de entregas) o extraerlo como servicio consumidor de `IncidentOpened` e `IncidentResolved`.

**Analytics:**
1. Consultas sobre `monitor_checks` (V1).
2. Rollups en un módulo `analytics` dentro de Core (o de Monitoring).
3. Servicio de Analytics con su propio almacén (PostgreSQL con rollups, TimescaleDB o ClickHouse) alimentado por eventos.

## 4 y 5. Ventajas y desventajas

| Alternativa | Ventajas | Desventajas |
|---|---|---|
| Notifications como módulo | Simple. La tabla de entregas ya aísla los reintentos | Un proveedor externo lento consume recursos de Core. Se despliega con Core |
| Notifications como servicio | Aislamiento de fallos y límites de terceros. Escalado propio | Otro servicio para una carga que suele ser baja (se notifica por incidente, no por check) |
| Analytics sobre los datos crudos | Sin nada nuevo | Límite de la retención. Costo de las consultas largas |
| Módulo `analytics` con rollups | Un paso intermedio barato | Comparte base de datos y despliegue |
| Servicio de Analytics | Almacén adecuado para analítica y replay del histórico | El servicio más caro de operar. Solo compensa con necesidades analíticas reales |

## 6. ¿Qué elegimos?

**Nada hasta tener datos.** Orden previsto: **primero** un módulo `analytics` dentro del monolito cuando lleguen los rollups (Fase 9), y solo después, con evidencia, un servicio.

Disparadores para extraer:

| Servicio | Disparador |
|---|---|
| Notifications | Entregas con p95 de encolado a envío > 1 min de forma sostenida por falta de capacidad; o fallos de proveedores externos que afectan de forma medible a Core; o más de ~3 tipos de canal con dependencias propias |
| Analytics | Consultas analíticas que superan el presupuesto de latencia incluso con rollups; o necesidad de replay del histórico de checks; o un almacén analítico distinto de PostgreSQL justificado por ADR-008 |

## 7. ¿Por qué?

El volumen de las notificaciones es bajo por diseño (depende de los incidentes, no de los checks) y la tabla de entregas ya aísla los reintentos. Analytics no existe como necesidad todavía. Extraer cualquiera de los dos sin datos sería un microservicio prematuro.

## 8. ¿Qué costo introduce, si se aprueba?

El mismo tipo de costo que ADR-011: contratos de eventos, idempotencia, despliegue, trazas y autorización (Notifications necesita conocer los canales de cada organización; Analytics necesita las membresías para servir consultas).

## 9. ¿Cómo comprobaremos que funciona, si se aprueba?

- Notifications: la latencia de encolado a envío y la tasa de fallos no empeoran, y un proveedor caído no afecta al p95 de Core.
- Analytics: las consultas analíticas cumplen su presupuesto y los resultados coinciden con el cálculo sobre los datos crudos en ventanas de prueba.

## 10. ¿Qué tendría que pasar para reconsiderarla?

Los disparadores de la sección 6. Si no aparecen, el ADR se cierra como "Rechazado: no fue necesario".
