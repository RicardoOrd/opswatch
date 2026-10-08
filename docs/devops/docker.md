# Docker

Estado: implementado (OW-004 y OW-009) · Última revisión: 2026-09-28

Ficheros: [`Dockerfile`](../../Dockerfile), [`.dockerignore`](../../.dockerignore) y [`docker-compose.yml`](../../docker-compose.yml). Este documento explica sus decisiones. Si difieren, manda el código.

## Objetivos

- Imagen pequeña, reproducible y sin herramientas de compilación.
- Ejecución como usuario no root, con el código de la aplicación de solo lectura.
- Ningún secreto dentro de la imagen.
- Healthchecks que reflejen el estado real de la aplicación.
- Un solo `docker compose up` para tener el entorno local.

## Dockerfile multi-stage

1. **build** (`eclipse-temurin:25-jdk-alpine`): descarga las dependencias en una capa propia, que se cachea mientras no cambie el `pom.xml`. Después compila `src/main` sin tests y extrae las capas de Spring Boot.
2. **runtime** (`eclipse-temurin:25-jre-alpine`): crea el usuario 10001 y copia las cuatro capas en `/app` (`opswatch.jar` más `lib/`). Declara el healthcheck y arranca con `java -jar opswatch.jar`.

| Decisión | Motivo |
|---|---|
| JDK solo en la etapa de build y JRE en la de ejecución | Sin compilador ni Maven en producción: menos superficie y menos tamaño |
| `eclipse-temurin:25-jre-alpine` | Imagen oficial y pequeña. Si Alpine (musl) diera algún problema con una dependencia nativa, se cambia a la variante Ubuntu (`25-jre`) |
| Imágenes base fijadas por digest | El digest es el del índice multiplataforma (amd64 y arm64). Dependabot (ecosistema `docker`) lo actualiza |
| Capas de Spring Boot | Un cambio de código solo invalida la capa `application` (57 KB), no los 70 MB de dependencias |
| Cache mount de `~/.m2` | Builds repetidos sin volver a descargar dependencias ni Maven |
| `-Dmaven.test.skip=true` | Los tests corren en el job `build` de CI. Aquí ni se compilan: `src/test` no entra en el contexto |
| Usuario 10001 sin shell ni home | No root. UID alto para no coincidir con usuarios del host |
| Ficheros propiedad de root | Un proceso comprometido no puede modificar el código de la aplicación |
| `MaxRAMPercentage=75` | La JVM respeta el límite de memoria del contenedor y deja margen para el resto (virtual threads, buffers, metaspace) |
| `ExitOnOutOfMemoryError` | Ante un OOM, el contenedor muere y Docker lo reinicia, en lugar de quedar zombi |
| Flags en `JAVA_TOOL_OPTIONS` | Se pueden cambiar sin reconstruir la imagen. La JVM escribe `Picked up JAVA_TOOL_OPTIONS: …` en stderr al arrancar, y es lo esperado |
| Sin flags de DNS | La cache DNS del [motor](../architecture/monitoring-engine.md#dns) (30 s, y 10 s para los fallos) coincide con los valores por defecto del JDK 25. Además, `-Dnetworkaddress.cache.ttl` no tiene efecto: es una *security property*, no una system property (comprobado en OW-009) |
| Healthcheck contra `liveness` en el puerto de management | `wget` viene en Alpine (busybox). No hace falta instalar `curl` |
| Sin `ARG` ni `ENV` con secretos | Quedarían en las capas y en `docker history` |

Comprobaciones de OW-009:

```bash
docker run --rm --entrypoint id opswatch:local   # uid=10001(opswatch) gid=10001(opswatch)
docker history --no-trunc opswatch:local         # sin secretos: solo instrucciones y JAVA_TOOL_OPTIONS
```

`docker run --rm opswatch:local id` **no** sirve para comprobar el usuario: con un `ENTRYPOINT` en forma exec, `id` llega a la aplicación como argumento.

### Tamaño

Medido en OW-009 (2026-09-28):

| Parte | Sin comprimir |
|---|---|
| Alpine y paquetes de la imagen de Temurin | 31 MB |
| JRE Temurin 25 (`/opt/java/openjdk`) | 198 MB |
| Dependencias (`lib/`) | 70 MB |
| Aplicación (`opswatch.jar`) | 57 KB |
| **Total** | **299 MB** (**138 MB comprimida**) |

**Objetivo: menos de 200 MB comprimida.** Es lo que se descarga en cada despliegue y lo que ocupa en GHCR. El objetivo inicial, menos de 250 MB sin comprimir, contaba con un JRE de 100 a 150 MB. El de Temurin 25 ocupa 198 MB, y unos 60 de ellos son cuatro archivos CDS (uno por combinación de *compressed oops* y *compact object headers*) de los que solo se usa uno.

**`jlink`, evaluado de nuevo en OW-009 y aplazado.** El experimento usó un JRE a medida con los módulos de `jdeps` más `jdk.management`, `jdk.crypto.ec`, `jdk.unsupported`, `jdk.zipfs`, `jdk.localedata` y `jdk.charsets`, sobre `alpine:3.24`. Arrancó bien:

| | Sin comprimir | Comprimida |
|---|---|---|
| Temurin 25 | 299 MB | 138 MB |
| `jlink` | 186 MB | 127 MB |

Solo ahorra 11 MB en lo que se transfiere. No compensa mantener la lista de módulos, porque un módulo olvidado solo falla en ejecución, y quizá en un camino poco usado. Se reconsidera si la imagen comprimida se acerca al objetivo.

La cache AOT del JDK (JEP 483 y relacionados) sigue aplazada: la aplicación arranca en unos 4 s.

## `.dockerignore`

Es una **lista de permitidos**: todo queda fuera salvo `.mvn/`, `mvnw`, `pom.xml` y `src/main/`. Una lista de excluidos obliga a acordarse de cada fichero sensible nuevo. Con una de permitidos, ni `.env`, ni `secrets/`, ni una clave suelta, ni el historial de Git pueden llegar al contexto de build, y por tanto tampoco a una capa.

## `docker-compose.yml` (entorno local, V1)

| Servicio | Arranque | Qué hace |
|---|---|---|
| `postgres` | Siempre | `postgres:18-alpine` fijado por digest. Puerto 5432 solo en `127.0.0.1` y volumen `postgres-data` en `/var/lib/postgresql` (`PGDATA` es `/var/lib/postgresql/18/docker` desde la imagen 18). Healthcheck por TCP, porque el servidor temporal de la inicialización solo escucha en el socket Unix. `PostgresTestcontainer` lee la imagen de aquí |
| `app` | Solo con `--profile app` | La imagen de este Dockerfile con el perfil `local`. Espera a que `postgres` esté *healthy* |

Endurecimiento del servicio `app`, comprobado en OW-009:

| Opción | Efecto |
|---|---|
| `read_only: true` más `tmpfs: /tmp` | El sistema de ficheros es de solo lectura. Tomcat y la JVM escriben solo en `/tmp` |
| `cap_drop: [ALL]` | Sin capabilities (`CapEff` a 0): escuchar en 8080 no las necesita |
| `no-new-privileges` | Ningún binario puede ganar privilegios (`NoNewPrivs: 1`) |
| `mem_limit: 1g`, `cpus: 2` | Límites de recursos; la JVM calcula el heap sobre el límite de memoria |
| `stop_grace_period: 40s` | El apagado ordenado espera hasta 35 s. Los 10 s por defecto de Docker lo cortarían con un `SIGKILL` |
| Puertos 8080 y 8081 solo en `127.0.0.1` | Management se publica **solo en local**, para comprobar la readiness. En los despliegues no se publica |

Las variables llegan con `env_file: .env`. El perfil `local` las usa igual que cuando lee `.env` desde el IDE, y `SPRING_DATASOURCE_URL` apunta al servicio `postgres`. En local las variables se ven en `docker inspect`: en staging y producción los secretos van como Docker secrets (ver más abajo).

Uso:

```bash
docker compose up -d --wait postgres              # desarrollo diario: la app desde el IDE con el perfil local
docker compose --profile app up -d --build --wait # todo en contenedores
docker compose --profile app down                 # parar (el volumen de datos se conserva)
docker compose --profile app down -v              # parar y BORRAR los datos locales
```

Las credenciales salen de `.env`, que `scripts/dev-keys.sh` crea con una contraseña aleatoria. Compose lo lee solo, y la aplicación con el perfil `local` lo importa como fuente de propiedades ([entornos](environments.md#env-y-perfil-local)). PostgreSQL fija la contraseña al crear el volumen: cambiarla después en `.env` exige `docker compose down -v`.

Alternativa de comodidad evaluada en OW-004 y **no adoptada**: el soporte de Docker Compose de Spring Boot (`spring-boot-docker-compose`) arranca `postgres` al lanzar la aplicación. Ahorra un comando, pero añade una dependencia que arranca contenedores desde la aplicación y esconde de dónde salen las credenciales. `docker compose up` explícito más `.env` es suficiente.

## Servicios que llegan después

Cada uno entra en su fase, con un profile de Compose para que no arranque si no se pide:

| Servicio | Profile | Fase | Para qué |
|---|---|---|---|
| `mailpit` | `mail` | 4 | Servidor SMTP falso con interfaz web para ver los emails en local. **Llegó en OW-036**: `docker compose --profile mail up -d mailpit`, SMTP en `localhost:1025` (el perfil `local` lo usa) y los emails en `http://localhost:8025`. Imagen fijada por digest |
| `prometheus` | `observability` | 7 | Recoger las métricas de `/actuator/prometheus` |
| `grafana` | `observability` | 7 | Dashboards |
| `target-simulator` | `benchmark` | 7 | Destinos simulados con latencia y fallos configurables ([benchmarks](../performance/benchmark-plan.md)) |
| `redis` | `cache` | 9, **si** [ADR-009](../adr/ADR-009-redis.md) se acepta | — |
| Broker | `broker` | 11, **si** [ADR-010](../adr/ADR-010-event-broker.md) se acepta | — |

## Producción (Fase 6)

`docker-compose.prod.yml` en el servidor, con estas diferencias respecto al local:

- `image: ghcr.io/ricardoord/opswatch:<versión>`, **sin `build`**: el servidor no compila.
- Secretos como **Docker secrets** montados en `/run/secrets/…` y leídos con `configtree` ([entornos](environments.md)), no como variables de entorno. Las variables se ven en `docker inspect` y en `/proc/<pid>/environ`.
- Sin puertos publicados: la aplicación escucha en la red interna y Caddy la alcanza por el nombre del servicio. PostgreSQL no se publica ni en loopback.
- Caddy con IP fija en la red de cada entorno (`ipv4_address` sobre una subred declarada), porque la aplicación solo acepta `X-Forwarded-For` desde esa IP: `SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES=<IP de Caddy>/32` ([seguridad](../security/security-architecture.md#5-transporte)). Sin `trusted_proxies` en el Caddyfile, Caddy sustituye el `X-Forwarded-For` que envía el cliente.
- `restart: unless-stopped`.
- Límites de CPU y memoria acordes con el VPS.
- Logs en JSON con rotación del driver (`max-size`, `max-file`).
- Un proyecto de Compose por entorno (`opswatch-prod` y `opswatch-staging`) con volúmenes y redes separados.

## Escaneo

- Trivy analiza la imagen en CI tras el build. La build falla con vulnerabilidades `CRITICAL` o `HIGH` que tengan corrección disponible ([CI/CD](ci-cd.md)).
- Dependabot vigila las imágenes base fijadas por digest.
