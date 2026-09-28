# Architecture Decision Records

Un ADR registra una decisión de arquitectura importante: el problema, las alternativas, qué se eligió, por qué, qué cuesta y qué haría cambiarla.

## Estados

| Estado | Significado |
|---|---|
| **Propuesto** | Analizado, pero sin decidir. Espera evidencia (los ADR de etapas futuras) |
| **Aceptado** | Decidido. Se implementa, o se implementó |
| **Rechazado** | Se analizó y se decidió no hacerlo |
| **Reemplazado por ADR-XXX** | Una decisión posterior lo sustituye. El ADR antiguo no se edita para cambiar su contenido; solo se actualiza su estado |

## Índice

| ADR | Título | Estado | Fecha |
|---|---|---|---|
| [001](ADR-001-modular-monolith.md) | Monolito modular para V1 | Aceptado | 2026-09-28 |
| [002](ADR-002-postgresql.md) | PostgreSQL como base de datos principal | Aceptado | 2026-09-28 |
| [003](ADR-003-spring-modulith.md) | Spring Modulith para los límites de módulo | Aceptado | 2026-09-28 |
| [004](ADR-004-security-strategy.md) | Estrategia de autenticación y autorización | Aceptado | 2026-09-28 |
| [005](ADR-005-internal-events.md) | Eventos internos: síncronos para invariantes, asíncronos para efectos | Aceptado | 2026-09-28 |
| [006](ADR-006-check-scheduling.md) | Programación de checks sobre PostgreSQL con `SKIP LOCKED` | Aceptado | 2026-09-28 |
| [007](ADR-007-http-client-and-concurrency.md) | Apache HttpClient 5 bloqueante sobre virtual threads | Aceptado | 2026-09-28 |
| [008](ADR-008-check-results-storage.md) | Resultados de checks en una tabla con retención | Aceptado | 2026-09-28 |
| [009](ADR-009-redis.md) | Redis | Propuesto | 2026-09-28 |
| [010](ADR-010-event-broker.md) | Broker de eventos: Kafka o RabbitMQ | Propuesto | 2026-09-28 |
| [011](ADR-011-monitoring-extraction.md) | Extracción de Monitoring como servicio | Propuesto | 2026-09-28 |
| [012](ADR-012-notifications-analytics-extraction.md) | Extracción de Notifications y Analytics | Propuesto | 2026-09-28 |
| [013](ADR-013-realtime-transport.md) | Transporte de tiempo real: SSE o WebSocket | Propuesto | 2026-09-28 |

"Aceptado" en esta etapa significa decidido **antes** de implementar. Cada ADR aceptado dice cómo se comprobará que funciona. Si la implementación o los benchmarks lo contradicen, se escribe un ADR nuevo que lo reemplace.

Las decisiones que todavía no justifican ni un ADR propuesto están en [decisiones abiertas](../architecture/open-decisions.md).

## Proceso

1. Un ADR nuevo se añade en un PR, con el número siguiente y el estado "Propuesto".
2. Si se acepta, se cambia a "Aceptado" en el mismo PR que empieza a implementarlo, o en uno propio.
3. Los documentos afectados (arquitectura, catálogo de propiedades, decisiones abiertas) se actualizan en el mismo PR.
4. Nombre del fichero: `ADR-NNN-titulo-en-kebab-case.md`.

## Plantilla

```markdown
# ADR-NNN: Título

- **Estado:** Propuesto | Aceptado | Rechazado | Reemplazado por ADR-XXX
- **Fecha:** AAAA-MM-DD
- **Relacionado:** enlaces a documentos y ADR

## 1. ¿Qué problema existe?
## 2. ¿Cuáles son los requisitos?
## 3. ¿Qué alternativas tenemos?
## 4. Ventajas de cada alternativa
## 5. Desventajas de cada alternativa
## 6. ¿Qué elegimos?
## 7. ¿Por qué?
## 8. ¿Qué costo o complejidad introduce?
## 9. ¿Cómo comprobaremos que funciona?
## 10. ¿Qué tendría que pasar para reconsiderarla?
```

Las secciones 4 y 5 pueden ir juntas en una tabla cuando se lee mejor.
