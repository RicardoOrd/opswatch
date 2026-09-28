# ADR-010: Broker de eventos: Kafka o RabbitMQ

- **Estado:** Propuesto. Se decide al abrir la Fase 11, o en la Fase 10 si ya hay dos o más consumidores entre procesos
- **Fecha:** 2026-09-28
- **Relacionado:** [Eventos internos](../architecture/events.md#8-evolución-hacia-eventos-distribuidos) · [Evolución, Etapa 4](../architecture/evolution.md#etapa-4-dirigida-por-eventos) · [ADR-005](ADR-005-internal-events.md)

## 1. ¿Qué problema existe?

**En V1, ninguno:** los eventos viajan dentro de un proceso con el registro de Spring Modulith. El problema aparecerá si se extraen servicios y varios procesos necesitan los mismos eventos:

- `MonitorWentDown` y `MonitorRecovered` para Core (incidentes), Notifications y Analytics;
- `MonitorCheckCompleted` (volumen alto) para Analytics y el tiempo real;
- `IncidentOpened` e `IncidentResolved` para Notifications y Analytics.

## 2. ¿Cuáles son los requisitos? (a validar con datos)

| Criterio | Estimación actual |
|---|---|
| Throughput | Transiciones: pocas por minuto. `MonitorCheckCompleted`: de ~17/s (1 000 monitores a 60 s) a ~333/s (10 000 a 30 s) |
| Orden | Por monitor, no global |
| Replay | Deseable si Analytics debe recalcular agregados desde el histórico |
| Persistencia | Sin pérdidas en las transiciones. `MonitorCheckCompleted` tolera pérdidas puntuales si la fuente de verdad es la base de datos |
| Routing | Por tipo de evento y posiblemente por organización |
| Work queues | Las entregas de notificaciones reparten trabajo entre varios workers |
| Consumer groups | Varias instancias de un mismo consumidor que se reparten el trabajo |
| Complejidad operativa | Un VPS pequeño y una persona operando |

## 3. ¿Qué alternativas tenemos?

1. **REST entre servicios con outbox y reintentos** (la opción de la Etapa 3 inicial).
2. **Outbox en PostgreSQL con polling por los consumidores** (tabla compartida o endpoints de feed).
3. **RabbitMQ** (colas y exchanges, más RabbitMQ Streams si hace falta replay).
4. **Kafka** (log particionado, modo KRaft sin ZooKeeper).
5. Redis Streams.
6. NATS JetStream.

## 4 y 5. Comparación

| Criterio | REST + outbox | Outbox con polling | RabbitMQ | Kafka | Redis Streams | NATS JetStream |
|---|---|---|---|---|---|---|
| Throughput a la escala prevista | Suficiente | Suficiente | Sobrado | Sobrado | Sobrado | Sobrado |
| Orden por clave | Manual (`transitionSeq`) | Por consulta | Por cola (una cola por partición lógica) o con *single active consumer* | **Nativo por partición** | Por stream | Por subject |
| Replay | No | Limitado (lo que retenga la tabla) | Solo con Streams | **Nativo** (retención del log) | Sí (con recorte) | Sí |
| Persistencia | En el outbox del productor | En la base de datos | Colas durables | Log replicado | En memoria con AOF o RDB | En disco |
| Routing flexible | Código | Consultas | **Exchanges (topic, headers)** | Por topic y clave | Básico | Subjects jerárquicos |
| Work queues | Manual | `SKIP LOCKED` | **Nativo** | Consumer groups (el paralelismo lo limita el número de particiones) | Consumer groups | Nativo |
| Fan-out a N consumidores | N llamadas y N reintentos | Cada consumidor lee | Exchanges fanout o topic | Cada grupo lee el log | Grupos | Sí |
| Ecosistema Spring | `RestClient` | JDBC | Spring AMQP, maduro | Spring for Apache Kafka, maduro | Spring Data Redis | Menor |
| Complejidad operativa | Baja | Baja | **Media-baja** (un contenedor ligero) | Media (KRaft simplifica, pero hay particiones, retención y más memoria) | Baja si Redis ya existe | Baja-media |
| Recursos mínimos orientativos | 0 | 0 | ~150–300 MB de RAM | ~1 GB o más de RAM razonable | Según Redis | Bajos |

## 6. ¿Qué elegimos?

**Todavía nada.** Hipótesis actual, **no decidida**:

- Si los consumidores necesitan sobre todo **fan-out de transiciones y colas de trabajo** (notificaciones), **RabbitMQ** cubre el caso con menos operación.
- Si **Analytics necesita replay** del histórico de `MonitorCheckCompleted` o reprocesar agregados, **Kafka** compensa su costo operativo.
- Si hay un solo consumidor, el **outbox con REST** de la Etapa 3 basta y no se introduce un broker.

## 7. ¿Por qué no se decide ahora?

Los criterios que inclinan la balanza (el número de consumidores, la necesidad de replay y el volumen real de `MonitorCheckCompleted`) no existen todavía. Elegir Kafka hoy sería elegir por su presencia en el CV, justo lo que el proyecto quiere evitar.

## 8. ¿Qué costo introduce cualquiera de las opciones?

- Un contenedor más en Compose, con su almacenamiento y vigilancia.
- Contratos de eventos versionados ([versionado](../development/versioning.md#eventos)).
- Consumidores idempotentes y colas o topics de mensajes fallidos.
- Trazas propagadas a través del broker.
- Tests con Testcontainers del broker elegido.

## 9. ¿Cómo comprobaremos la elección?

- Prototipo de los dos candidatos con el mismo escenario: `MonitorWentDown` con 3 consumidores y `MonitorCheckCompleted` a 333/s, midiendo la latencia de extremo a extremo p95, la memoria, la CPU y la complejidad de la configuración.
- Prueba de caída del broker: el outbox de Spring Modulith retiene los eventos y los envía al volver.
- Prueba de replay (solo si es un requisito).

## 10. ¿Qué tendría que pasar para decidir?

- Dos o más consumidores de los mismos eventos en procesos distintos.
- El outbox con REST acumula reintentos o retrasos visibles en las métricas.
- Aparece un requisito firme de replay.

Si ninguno ocurre, este ADR se cierra como "Rechazado: no fue necesario", con los datos.
