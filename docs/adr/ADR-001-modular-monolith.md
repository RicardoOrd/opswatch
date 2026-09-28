# ADR-001: Monolito modular para V1

- **Estado:** Aceptado
- **Fecha:** 2026-09-28
- **Relacionado:** [Módulos](../architecture/modules.md) · [Evolución](../architecture/evolution.md) · [ADR-003](ADR-003-spring-modulith.md) · [ADR-011](ADR-011-monitoring-extraction.md)

## 1. ¿Qué problema existe?

Hay que elegir la forma de desplegar y estructurar el backend de V1. La elección condiciona el costo operativo, la velocidad de desarrollo y la facilidad de evolucionar.

## 2. ¿Cuáles son los requisitos?

- Una persona desarrolla y opera.
- Presupuesto de infraestructura bajo.
- Límites de dominio claros (identity, organizaciones, monitoreo, incidentes, notificaciones), porque el proyecto quiere mostrar diseño.
- Poder escalar el motor de checks más adelante sin reescribirlo.
- Consistencia fuerte entre el estado del monitor y los incidentes.

## 3. ¿Qué alternativas tenemos?

1. Monolito por capas (`controller/`, `service/`, `repository/`).
2. **Monolito modular** (paquetes por dominio con límites verificados).
3. Microservicios desde el principio (Core, Monitoring, Notifications…).
4. Funciones serverless para el motor y una API aparte.

## 4 y 5. Ventajas y desventajas

| Alternativa | Ventajas | Desventajas |
|---|---|---|
| Monolito por capas | Lo más simple de empezar | Los límites de dominio se diluyen. Todo depende de todo con el tiempo. Extraer un módulo después es caro |
| **Monolito modular** | Un despliegue, una base de datos y transacciones locales. Límites explícitos y verificables. Extraer después es viable | Exige disciplina (o una herramienta) para que los límites no se erosionen |
| Microservicios | Escalado y despliegue independientes | Red, consistencia eventual, observabilidad distribuida y N pipelines desde el primer día, **sin ningún problema que lo justifique**. Multiplica el costo para una persona |
| Serverless | Escala a cero | Checks periódicos de larga duración que encajan mal. Arranques en frío. Dependencia del proveedor. Control limitado del DNS y la red, que la protección SSRF necesita |

## 6. ¿Qué elegimos?

Un **monolito modular**: una aplicación Spring Boot con siete módulos de dominio (`identity`, `organization`, `monitoring`, `incident`, `notification`, `egress` y `shared`) y límites verificados en cada build.

## 7. ¿Por qué?

- No hay evidencia de que ningún módulo necesite escalar ni desplegarse por separado. Sin esa evidencia, los microservicios solo añaden costo.
- La invariante estado del monitor ↔ incidente se resuelve con una transacción local, sin sagas.
- Los límites por dominio dejan preparada la extracción si los datos la piden ([evolución](../architecture/evolution.md)).
- El motor ya puede escalar horizontalmente dentro del monolito (`SKIP LOCKED` y la separación por rol) antes de necesitar un servicio aparte.

## 8. ¿Qué costo o complejidad introduce?

- Disciplina de límites: resuelta con Spring Modulith ([ADR-003](ADR-003-spring-modulith.md)).
- Todos los módulos se despliegan juntos: un fallo de memoria del motor afecta a la API. Es aceptable en V1 y se mitiga con el semáforo y los límites.
- Una sola base de datos compartida: las FK entre módulos quedan documentadas para una posible extracción.

## 9. ¿Cómo comprobaremos que funciona?

- `ApplicationModules.verify()` en verde en cada PR.
- La mayoría de los PR de funcionalidad tocan un solo módulo, lo que se revisa al cerrar cada fase.
- Benchmarks de la Fase 7: la aplicación sostiene la carga objetivo de V1 (1 000 monitores a 60 s en 2 vCPU con el lag p95 < 2 s).

## 10. ¿Qué tendría que pasar para reconsiderarla?

- Los criterios C1 a C4 de [ADR-011](ADR-011-monitoring-extraction.md) se cumplen tras optimizar y separar por rol.
- Varias personas trabajando en paralelo en módulos distintos con conflictos de despliegue frecuentes.
- Un módulo necesita una tecnología de ejecución incompatible con el resto (poco probable: todo es Java y Spring).
