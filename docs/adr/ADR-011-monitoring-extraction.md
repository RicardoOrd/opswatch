# ADR-011: Extracción de Monitoring como servicio

- **Estado:** Propuesto. Se decide tras los benchmarks de las Fases 7 y 9
- **Fecha:** 2026-09-28
- **Relacionado:** [Evolución, Etapa 3](../architecture/evolution.md#etapa-3-monitoring-extraído) · [Plan de benchmarks](../performance/benchmark-plan.md) · [ADR-001](ADR-001-modular-monolith.md) · [ADR-005](ADR-005-internal-events.md)

## 1. ¿Qué problema existe?

**Hipotético:** que el motor de checks necesite escalar, desplegarse o aislarse de forma independiente de la API, y que eso no pueda resolverse dentro del monolito.

## 2. ¿Cuáles son los requisitos?

- Evidencia medida con el procedimiento del [plan de benchmarks](../performance/benchmark-plan.md). No basta con la intuición.
- Haber agotado antes las alternativas más baratas.
- Una extracción que no pierda correctitud: ni incidentes duplicados ni perdidos.
- Rollback posible en cada paso.

## 3. ¿Qué alternativas tenemos?

1. Seguir en el monolito, optimizando (inserciones en lote, pool, consultas).
2. Escalado vertical (hasta 4 vCPU).
3. **Separación por rol:** la misma imagen desplegada como API y como worker ([Etapa 2b](../architecture/evolution.md#etapa-2b-separación-por-rol-sin-nuevo-servicio)).
4. Pasar Monitoring a un módulo de Maven (límites de compilación más fuertes, mismo despliegue).
5. **Extraer Monitoring** como servicio Spring Boot con su propio esquema o base de datos.

## 4 y 5. Ventajas y desventajas

| Alternativa | Ventajas | Desventajas |
|---|---|---|
| Optimizar | Sin cambios de arquitectura | Tiene un límite |
| Escalado vertical | Inmediato | Costo, y tiene un límite |
| Separación por rol | Escalado independiente sin código nuevo. Aísla la CPU de la API | Misma base de datos, mismo despliegue, el worker lleva todo el código |
| Módulo de Maven | Límites más fuertes, paso previo a la extracción | Sin beneficio operativo |
| Extracción | Escalado, despliegue, datos y red independientes | Consistencia distribuida, más operación, contratos que versionar, autorización entre servicios y trazas distribuidas imprescindibles |

## 6. ¿Qué elegimos?

**Nada hasta tener datos.** La extracción se aprueba si, **después** de aplicar las alternativas 1 a 3, se cumple **al menos uno** de estos criterios:

| # | Criterio | Umbral medido |
|---|---|---|
| C1 | Contención en la base de datos atribuible a `monitor_checks` | p95 de las consultas de la API > 2 × el baseline con el motor a carga objetivo, según `pg_stat_statements` |
| C2 | El motor no sostiene la carga con los recursos de un worker del monolito | Lag p95 > 5 s o > 10 % del intervalo durante ≥ 15 min con CPU < 70 % |
| C3 | Huella de recursos | RSS del worker del monolito > 2 × la de un prototipo solo con el motor, por el número de workers necesarios |
| C4 | Acoplamiento de despliegue | Huecos de checks > 1 intervalo en más del 1 % de los monitores por despliegue de la API |

Si **ninguno** se cumple a 10 000 monitores a 30 s, este ADR se cierra como **Rechazado**, con los resultados. Esa conclusión es tan valiosa para el portafolio como la extracción.

## 7. ¿Por qué estos criterios?

Miden las razones legítimas para separar un servicio (**datos, capacidad, recursos y despliegue**) y descartan las ilegítimas (moda, CV). Exigir que antes se agoten las alternativas evita pagar el costo distribuido por un problema que se resolvía con configuración.

## 8. ¿Qué costo introduce, si se aprueba?

El plan, los riesgos y el rollout están en la [Etapa 3 de la evolución](../architecture/evolution.md#etapa-3-monitoring-extraído). Resumen:

- Paso de `monitoring` → `incident` de síncrono a asíncrono, con `transitionSeq` e idempotencia.
- Eliminar las FK entre módulos y crear un esquema o base de datos propia.
- Endpoints internos versionados, o broker ([ADR-010](ADR-010-event-broker.md)).
- Autorización en el servicio: JWKS de Core más membresías por consulta o por proyección.
- Trazas distribuidas, un pipeline y un despliegue más.

## 9. ¿Cómo comprobaremos que la extracción funcionó?

- El criterio que la motivó mejora de forma medible (por ejemplo, C1: el p95 de la API vuelve a su baseline).
- Durante el canary por shards: incidentes por caída = 1, sin diferencias con el periodo anterior.
- Rollback probado en al menos un paso.

## 10. ¿Qué tendría que pasar para reconsiderarla?

- Si tras extraer el costo operativo supera al beneficio medido: se vuelve a desplegar el módulo en el monolito. El código sigue siendo el mismo módulo, así que la vuelta es viable mientras no se hayan acumulado divergencias.
