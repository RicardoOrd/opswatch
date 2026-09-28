# ADR-005: Eventos internos: síncronos para invariantes, asíncronos para efectos

- **Estado:** Aceptado
- **Fecha:** 2026-09-28
- **Relacionado:** [Eventos internos](../architecture/events.md) · [Ciclo de vida de incidentes](../architecture/incident-lifecycle.md) · [ADR-003](ADR-003-spring-modulith.md) · [ADR-010](ADR-010-event-broker.md)

## 1. ¿Qué problema existe?

Cuando un monitor cae, `incident` tiene que abrir un incidente y `notification` tiene que avisar. Si `monitoring` llama a los otros módulos directamente, queda acoplado a todos sus consumidores (`MonitoringService → IncidentRepository`) y deja de ser extraíble. Además, cada reacción tiene necesidades de consistencia distintas.

## 2. ¿Cuáles son los requisitos?

- `monitoring` no depende de `incident` ni de `notification`.
- **Invariante:** un monitor `DOWN` tiene exactamente un incidente activo, sin ventanas de inconsistencia y con el orden de las transiciones respetado.
- Las notificaciones solo se envían por cambios confirmados, no se pierden si la aplicación cae y no se duplican hacia el usuario.
- Ningún I/O externo (SMTP, webhooks) dentro de las transacciones de negocio.
- Sin infraestructura nueva en V1.

## 3. ¿Qué alternativas tenemos?

1. Llamadas directas entre servicios de módulos.
2. Todos los eventos síncronos en la misma transacción.
3. Todos los eventos asíncronos después del commit, con registro (outbox).
4. **Mixto:** síncrono en la misma transacción donde hay una invariante; asíncrono con registro para los efectos laterales.
5. Broker externo desde V1.

## 4 y 5. Ventajas y desventajas

| Alternativa | Ventajas | Desventajas |
|---|---|---|
| Llamadas directas | Simple de leer | Acoplamiento en la dirección equivocada. Cada consumidor nuevo modifica el motor |
| Todo síncrono | Consistencia fuerte en todo | El SMTP dentro de la transacción: conexiones retenidas durante segundos y un fallo externo revierte los incidentes |
| Todo asíncrono con registro | Desacoplamiento máximo y parecido a lo distribuido | La invariante monitor ↔ incidente queda expuesta al desorden y a los duplicados. Obliga desde V1 a secuencias por monitor y a una idempotencia compleja |
| **Mixto** | Cada reacción tiene la garantía que necesita. Sin infraestructura nueva | Dos modos que entender. El síncrono acopla en tiempo de ejecución (misma transacción) |
| Broker desde V1 | Preparado para distribuir | Infraestructura y consistencia eventual sin necesidad ([ADR-010](ADR-010-event-broker.md)) |

## 6. ¿Qué elegimos?

- Comunicación entre módulos **por eventos de aplicación de Spring** (records en el paquete raíz del módulo publicador).
- **`monitoring` → `incident`:** `@EventListener` **síncrono en la misma transacción** (`MonitorWentDown`, `MonitorRecovered`, `MonitorPaused` y `MonitorDeleted`).
- **`incident` → `notification`** y **`organization` → `monitoring`:** `@ApplicationModuleListener` (asíncrono, después del commit y en una transacción nueva) con el **Event Publication Registry** de Spring Modulith como outbox.
- Idempotencia de los consumidores asíncronos con claves únicas.

## 7. ¿Por qué?

- La invariante estado ↔ incidente es correcta de forma trivial dentro de una transacción. El bloqueo de `monitor_state` serializa las transiciones de cada monitor y el orden queda garantizado.
- Las notificaciones necesitan exactamente lo que da el registro: solo lo confirmado (`AFTER_COMMIT`), sin pérdidas (se reenvían al reiniciar) y fuera de la transacción de negocio.
- La dirección de las dependencias queda bien: los consumidores dependen del contrato del publicador.

## 8. ¿Qué costo o complejidad introduce?

- Un fallo en el listener síncrono revierte el resultado del check. Ese check se pierde y el siguiente vuelve a evaluar la transición. Es aceptable y está documentado.
- La tabla `event_publication`, con su limpieza y la vigilancia de las publicaciones incompletas.
- **El modo síncrono no sobrevive a la extracción de Monitoring.** En la Etapa 3 habrá que añadir `transitionSeq`, idempotencia y medir el paso a asíncrono ([evolución](../architecture/evolution.md#pasos-de-la-extracción-strangler)). Es un costo conocido y aplazado a propósito.

## 9. ¿Cómo comprobaremos que funciona?

- `@ApplicationModuleTest` con `Scenario`: `MonitorWentDown` → `IncidentOpened`.
- Eventos duplicados: un solo incidente y una sola entrega.
- Reinicio con publicaciones pendientes: las entregas se crean una vez.
- Índice único parcial: dos incidentes activos para un monitor son imposibles a nivel de base de datos.
- Métrica `opswatch_event_publications_incomplete` en 0 en estado estable.

## 10. ¿Qué tendría que pasar para reconsiderarla?

- Extracción de Monitoring: la comunicación `monitoring` → `incident` pasa a ser asíncrona entre procesos.
- Consumidores nuevos con mucho volumen (por ejemplo, `MonitorCheckCompleted` para analytics o tiempo real): se publican **sin** registro persistente o por otro canal, para no cargar `event_publication`.
- Varios consumidores entre procesos con necesidad de fan-out o replay: broker ([ADR-010](ADR-010-event-broker.md)).
