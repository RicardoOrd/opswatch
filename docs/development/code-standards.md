# Estándares de código

Estado: diseño inicial · Última revisión: 2026-09-28

## Paquetes

- Paquete raíz: `io.github.ricardoord.opswatch` (convención de Maven para proyectos personales alojados en GitHub).
- Un subpaquete por módulo, con la estructura interna de [modules.md](../architecture/modules.md#5-estructura-interna-de-un-módulo).
- La API pública de un módulo va en la raíz del módulo. Los subpaquetes son internos.
- No hay paquetes `util` ni `helpers` genéricos. Una función auxiliar vive en el paquete que la usa. Si la usan dos módulos, va a `shared` con un nombre concreto.

## Nombres

| Elemento | Convención | Ejemplo |
|---|---|---|
| Entidad | Sustantivo del dominio | `Monitor`, `Incident` |
| Repositorio | `<Entidad>Repository` | `MonitorRepository` |
| Caso de uso o servicio | `<Concepto>Service`, o un nombre de rol cuando aclara más | `MonitorService`, `CheckResultRecorder` |
| API pública de un módulo | Interfaz con nombre de capacidad | `AccessControl`, `MonitorDirectory` |
| Evento | Hecho en pasado | `MonitorWentDown` |
| Listener | `<Origen>EventsListener` | `MonitorEventsListener` |
| Controlador | `<Recurso>Controller` | `MonitorController` |
| DTO | Ver la [guía de API](../api/api-guidelines.md#6-dtos) | `CreateMonitorRequest`, `MonitorResponse` |
| Excepción | `<Problema>Exception` | `ResourceNotFoundException`, `BlockedTargetException` |
| Test unitario o de integración | `<Clase>Test` o `<Clase>IT` | `StateTransitionTest`, `MonitorApiIT` |
| Constantes | `UPPER_SNAKE_CASE` | `MAX_REDIRECTS` |
| Propiedades | `opswatch.<área>.<nombre-en-kebab>` | `opswatch.monitoring.engine.max-concurrent-checks` |

Nombres de dominio en inglés en el código. Los comentarios pueden ir en inglés o en español, pero con coherencia dentro de cada fichero. Se prefiere inglés en el código y español en `docs/`.

## Lenguaje: Java 25

- **`record`** para DTOs, eventos, value objects y resultados (`ProbeRequest`, `CheckOutcome`).
- **`sealed` y pattern matching en `switch`** para resultados con varias formas (`HttpObservation`).
- **`var`** solo cuando el tipo es obvio por el lado derecho.
- **Inmutabilidad por defecto:** campos `final`, colecciones inmutables (`List.copyOf`) en los límites y records.
- Entidades JPA: clases (no records), con constructor protegido para JPA y constructores o factorías públicas que validan invariantes. Sin setters públicos para todo: se exponen métodos con intención (`monitor.pause(clock)`, `incident.acknowledge(user, note, clock)`).
- `equals` y `hashCode` de las entidades basados solo en el id (asignado en la construcción, porque es UUIDv7 generado en la aplicación).

## Lombok: no

| A favor | En contra |
|---|---|
| Menos boilerplate en las entidades | Los `record` ya eliminan el boilerplate en DTOs y eventos, que son la mayoría de clases |
| — | `@Data` en entidades JPA genera `equals`, `hashCode` y `toString` peligrosos (relaciones perezosas, identidad) |
| — | Un procesador de anotaciones más, con fricción en cada versión nueva del JDK |
| — | Para un portafolio, el código explícito se lee mejor |

Decisión: **sin Lombok**. El IDE genera los constructores y getters de las pocas entidades.

## Nulos

- **JSpecify:** `@NullMarked` en cada `package-info.java` (nada es nulo salvo que se diga) y `@Nullable` donde sí. Es el mismo modelo de anotaciones que usa Spring Framework 7.
- Verificación estática con NullAway: se evalúa en la Fase 5. Añade Error Prone al build y hay que medir la fricción.
- **`Optional`** solo como tipo de retorno de búsquedas que pueden no encontrar nada (`findActiveById`). Nunca en campos, parámetros ni colecciones.
- Colecciones: nunca `null`, siempre vacía.
- Las validaciones de entrada rechazan los nulos en el borde (DTOs). El dominio no recibe `null` donde no se espera.

## Excepciones

```text
RuntimeException
└── DomainException (shared)          code estable → Problem Details
    ├── ResourceNotFoundException     → 404
    ├── AccessDeniedException         → 403 (propia, no la de Spring Security)
    ├── ConflictException             → 409 (duplicados)
    ├── BusinessRuleViolationException→ 409
    ├── QuotaExceededException        → 422
    └── TargetNotAllowedException     → 422
```

- Todas unchecked. El `@RestControllerAdvice` de `shared` las traduce a Problem Details. Los controladores no capturan excepciones para devolver respuestas.
- Mensajes para el cliente sin datos internos. El detalle técnico va a la causa (`cause`) y al log.
- No se captura `Exception` de forma genérica salvo en los límites de tareas asíncronas (el motor y el worker de entregas), y ahí siempre se registra y se mide.
- No se usan excepciones para el control de flujo normal. Un check fallido **no** es una excepción del dominio: es un `CheckOutcome` `DOWN`.

## Logging

- SLF4J con `private static final Logger log = LoggerFactory.getLogger(...)`.
- Mensajes con parámetros (`log.info("Monitor {} went down", monitorId)`), sin concatenar.
- Qué registrar y qué no: [observabilidad](../devops/observability.md#logs).
- Ningún `toString()` de un objeto que contenga secretos sin redacción. Los records sensibles sobrescriben `toString()`.

## Mapeo entre capas

- Manual y explícito: `MonitorResponse.from(Monitor monitor)` y `request.toCommand()`.
- **Sin MapStruct:** hay pocos DTOs, y el mapeo tiene lógica (enmascarar headers, calcular campos). Un generador ocultaría justo lo que importa revisar.
- Los DTOs de `web` no llegan al dominio. El controlador los convierte en parámetros o comandos del caso de uso.

## Límites de los servicios y transacciones

- `@Transactional` solo en `application`. Métodos de solo lectura con `@Transactional(readOnly = true)`.
- **Ninguna llamada de red dentro de una transacción** (checks, webhooks, SMTP).
- Un caso de uso = un método público de servicio = una transacción. Nada de servicios que llaman a otros servicios transaccionales en cadena sin necesidad.
- La autorización se comprueba en el caso de uso, después de cargar el recurso ([modelo de autorización](../security/authorization-model.md#4-cómo-se-decide-en-cada-petición)).
- El tiempo sale de `Clock` y la aleatoriedad de `RandomGenerator`, siempre inyectados.
- Inyección por constructor. Nunca `@Autowired` en campos.

## Herramientas de calidad

| Herramienta | Decisión | Motivo |
|---|---|---|
| **Spotless** + palantir-java-format | **Sí, desde el Sprint 0** | El formato deja de discutirse. `spotless:apply` en local y `spotless:check` en CI |
| **Spring Modulith `verify()`** | **Sí** | Límites de módulo ([ADR-003](../adr/ADR-003-spring-modulith.md)) |
| **ArchUnit** (reglas propias) | **Sí, pocas** | Solo las que Modulith no cubre ([testing](../testing/testing-strategy.md#arquitectura)) |
| **JaCoCo** | Sí, solo como informe | Visibilidad sin umbral global |
| `-Xlint:all -Werror` | Sí | Warnings del compilador tratados como errores desde el principio |
| Checkstyle | **No** | Con un formateador automático, la mayoría de sus reglas son redundantes o subjetivas |
| SpotBugs | **No, por ahora** | Solapa con CodeQL (Fase 5) y el compilador, y tiene falsos positivos. Se reconsidera si CodeQL deja huecos |
| SonarCloud | **Opcional en la Fase 5** | Panel de calidad gratuito para repositorios públicos, vistoso para el portafolio. No aporta reglas críticas que no cubran las demás herramientas |
| Error Prone / NullAway | Se evalúa en la Fase 5 | Valor alto con nulos, pero complica el build |
| CodeQL | Sí, en la Fase 5 | Análisis estático de seguridad gratuito |
| PIT (mutation testing) | Opcional en la Fase 5, solo en `domain` y `egress` | Mide la calidad de los tests donde más importa |

## Revisión de código

Cada PR, aunque la persona que revisa sea la misma que escribe, pasa por esta lista (incluida en la plantilla de PR):

- [ ] ¿El cambio respeta los límites de módulo, o hay un ADR que los cambie?
- [ ] ¿Todo recurso cargado por id pasa por `AccessControl`?
- [ ] ¿Hay I/O externo dentro de una transacción?
- [ ] ¿Se registra algún dato sensible?
- [ ] ¿Las entradas nuevas se validan en el DTO y en el dominio?
- [ ] ¿Los tests cubren el caso de otra organización (IDOR)?
- [ ] ¿La documentación afectada se actualizó en este PR?
