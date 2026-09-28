# ADR-003: Spring Modulith para los límites de módulo

- **Estado:** Aceptado
- **Fecha:** 2026-09-28
- **Relacionado:** [Módulos](../architecture/modules.md) · [Eventos](../architecture/events.md) · [ADR-001](ADR-001-modular-monolith.md) · [ADR-005](ADR-005-internal-events.md)

## 1. ¿Qué problema existe?

Un monolito modular solo sigue siendo modular si algo impide que los módulos se acoplen con el tiempo. Además, los eventos entre módulos necesitan una garantía de entrega para no perder efectos (notificaciones) si la aplicación cae.

## 2. ¿Cuáles son los requisitos?

- Detectar en el build las dependencias no permitidas y los accesos a código interno de otro módulo.
- Ciclos prohibidos.
- Eventos entre módulos con una opción de persistencia (outbox) para los listeners asíncronos.
- Tests por módulo, sin arrancar toda la aplicación.
- Poco costo de adopción. Nada que obligue a reescribir si se abandona.

## 3. ¿Qué alternativas tenemos?

1. Solo convención (paquetes por módulo y revisión de código).
2. ArchUnit con reglas propias.
3. **Spring Modulith.**
4. Maven multi-módulo (un artefacto por módulo).
5. JPMS (módulos de Java).

## 4 y 5. Ventajas y desventajas

| Alternativa | Ventajas | Desventajas |
|---|---|---|
| Convención | Costo cero | No se cumple a la larga. Sin detección automática |
| ArchUnit propio | Flexible y maduro | Hay que escribir y mantener las reglas de módulo a mano. Sin soporte de eventos ni de tests por módulo |
| **Spring Modulith** | Detecta los módulos por paquete, `verify()` (se apoya en ArchUnit), dependencias permitidas declarativas, Event Publication Registry (outbox), `@ApplicationModuleTest`, `Scenario`, documentación generada | Otra dependencia con su ritmo de versiones. Convenciones propias (API en el paquete raíz, subpaquetes internos). Tabla `event_publication` |
| Maven multi-módulo | Límites en tiempo de compilación, los más fuertes | Más ceremonia de build (N `pom.xml`, versiones, empaquetado). Las entidades JPA que cruzan módulos son un problema. Refactorizar los límites cuesta más |
| JPMS | Encapsulación del lenguaje | Fricción con Spring, la reflexión, JPA y las librerías. Casi nadie lo usa en aplicaciones Spring |

## 6. ¿Qué elegimos?

**Spring Modulith**: verificación de la estructura, dependencias declaradas por módulo, Event Publication Registry para los listeners asíncronos y tests por módulo. Más unas pocas reglas ArchUnit propias para lo que Modulith no cubre.

## 7. ¿Por qué?

- Resuelve los dos problemas (límites y eventos fiables) con una sola herramienta integrada en Spring Boot.
- `verify()` convierte el diagrama de dependencias en un test que rompe el build.
- El registro de publicaciones es un outbox transaccional sin escribirlo a mano, y se puede externalizar hacia un broker en la Etapa 4.
- Mantiene la estructura por paquetes: si algún día se pasa a Maven multi-módulo (paso previo a una extracción), los límites ya estarán limpios.

## 8. ¿Qué costo o complejidad introduce?

- Aprender sus convenciones: la API pública en el paquete raíz, los subpaquetes internos y los módulos abiertos.
- Tabla `event_publication` en el esquema, con su migración y su limpieza.
- Compatibilidad de versiones con Spring Boot: se fija la versión del BOM de Modulith que corresponde a la de Spring Boot.

## 9. ¿Cómo comprobaremos que funciona?

- Sprint 0: una dependencia prohibida introducida a propósito rompe el build.
- Fase 4: un reinicio entre el commit y el listener no pierde la notificación (test).
- La documentación generada coincide con el diagrama de [modules.md](../architecture/modules.md).

## 10. ¿Qué tendría que pasar para reconsiderarla?

- `verify()` impide diseños legítimos de forma recurrente, o sus convenciones obligan a distorsionar el diseño.
- Fricción grave de versiones al actualizar Spring Boot.
- Antes de extraer un módulo, pasar ese módulo a un módulo de Maven puede dar límites más fuertes. Sería un complemento, no un reemplazo.
