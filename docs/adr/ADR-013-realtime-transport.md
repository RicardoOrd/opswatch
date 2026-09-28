# ADR-013: Transporte de tiempo real: SSE o WebSocket

- **Estado:** Propuesto. Se decide al empezar la Fase 8
- **Fecha:** 2026-09-28
- **Relacionado:** [Roadmap, Fase 8](../roadmap/roadmap.md#fase-8-tiempo-real-y-páginas-de-estado-v2) · [ADR-009](ADR-009-redis.md) · [Eventos](../architecture/events.md)

## 1. ¿Qué problema existe?

En la Fase 8 el dashboard debe reflejar sin recargar: los cambios de estado de los monitores, la apertura, el acknowledge y la resolución de incidentes, y el estado general de un proyecto.

REST sigue siendo el mecanismo de todas las operaciones (crear, editar, consultar). El tiempo real solo **empuja avisos** del servidor al cliente.

## 2. ¿Cuáles son los requisitos?

- Del servidor al cliente, con una latencia desde la transición hasta la pantalla < 2 s.
- Autenticación y autorización por organización o proyecto: un usuario solo recibe los eventos de lo que puede ver.
- Funcionar detrás de Caddy.
- Varias instancias en el futuro (fan-out).
- Reconexión automática sin perder el estado (el cliente vuelve a pedirlo por REST tras reconectar).

## 3. ¿Qué alternativas tenemos?

1. Polling de REST cada 15 a 30 s.
2. **Server-Sent Events (SSE).**
3. **WebSocket** (con STOMP en Spring, o sin él).

## 4 y 5. Ventajas y desventajas

| | Polling | SSE | WebSocket (STOMP) |
|---|---|---|---|
| Dirección | Cliente → servidor | Servidor → cliente | Bidireccional |
| Complejidad en el servidor | Ninguna | Baja (`SseEmitter` en Spring MVC, que encaja bien con virtual threads) | Media (broker de mensajes simple o relay, suscripciones, interceptores de seguridad por `SUBSCRIBE`) |
| Complejidad en el cliente | Ninguna | Baja (`EventSource`, reconexión incluida) | Media (cliente STOMP y reconexión a mano) |
| Autenticación | La habitual | `EventSource` **no permite headers**: hay que usar la cookie o un `fetch` con streaming | El navegador tampoco permite headers en el handshake: token en el primer mensaje (`CONNECT` de STOMP) o un ticket de un solo uso |
| Proxies e infraestructura | Sin problema | HTTP normal. Hay que desactivar el buffering en el proxy | Upgrade de protocolo. Caddy lo soporta |
| Fan-out entre instancias | No aplica | Hace falta pub/sub (`LISTEN/NOTIFY` o Redis) | Relay de broker (RabbitMQ STOMP) o pub/sub |
| Latencia | Hasta el intervalo | Inmediata | Inmediata |
| Valor para el portafolio | Bajo | Medio | Alto, pero **no es un criterio de decisión válido por sí solo** |

## 6. ¿Qué elegimos?

**Todavía nada.** Hipótesis: **SSE basta**, porque el flujo es solo del servidor al cliente y es la opción más simple que cumple los requisitos. WebSocket se elegiría si en la Fase 8 aparece alguna de estas necesidades:

- comunicación del cliente al servidor por el mismo canal (suscripciones dinámicas a muchos proyectos, acks, colaboración en incidentes);
- un volumen de mensajes o de conexiones en el que la eficiencia de WebSocket se note de forma medible;
- limitaciones de SSE con la autenticación elegida que no se resuelvan con `fetch` y streaming.

El polling queda como degradación si el canal de tiempo real falla.

## 7. ¿Por qué no se decide ahora?

La Fase 8 depende de decisiones que aún no existen (frontend, número de instancias, necesidad de fan-out). La hipótesis y los criterios quedan escritos para decidir rápido cuando llegue.

## 8. ¿Qué costo introduce?

Común a las dos opciones: conexiones abiertas por usuario (se mide en los benchmarks), autorización por canal, fan-out con varias instancias (disparador D3 de [ADR-009](ADR-009-redis.md)) y la publicación de eventos de estado (por ejemplo, `MonitorStatusChanged`) **sin** el registro persistente, porque es información efímera.

## 9. ¿Cómo comprobaremos que funciona?

- Latencia desde la transición hasta el evento en el cliente, p95 < 2 s.
- Test de autorización: un usuario sin acceso al proyecto no recibe sus eventos.
- Prueba de carga de conexiones concurrentes (k6 soporta WebSocket; para SSE, streaming HTTP).
- Reconexión tras reiniciar el servidor.

## 10. ¿Qué tendría que pasar para decidir?

Empezar la Fase 8 con los requisitos concretos del frontend.
