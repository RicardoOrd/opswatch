# Docker

Estado: diseño inicial · Última revisión: 2026-09-28

`docker-compose.yml` existe desde OW-004 con el servicio `postgres`. El Dockerfile, el `.dockerignore` y el servicio `app` son **bocetos de diseño** hasta OW-009.

## Objetivos

- Imagen pequeña, reproducible y sin herramientas de compilación.
- Ejecución como usuario no root, con el código de la aplicación de solo lectura.
- Ningún secreto dentro de la imagen.
- Healthchecks que reflejen el estado real de la aplicación.
- Un solo `docker compose up` para tener el entorno local.

## Dockerfile multi-stage

```dockerfile
# syntax=docker/dockerfile:1

# ---------- build ----------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Primero las dependencias: esta capa se cachea mientras el pom no cambie
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Los tests ya corrieron en el job de CI anterior; aquí solo se empaqueta
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests

# Capas de Spring Boot: dependencias, loader, snapshots y aplicación
RUN java -Djarmode=tools -jar target/opswatch.jar extract --layers --destination target/extracted

# ---------- runtime ----------
FROM eclipse-temurin:25-jre-alpine AS runtime

RUN addgroup -S -g 10001 opswatch && adduser -S -u 10001 -G opswatch -H -s /sbin/nologin opswatch

WORKDIR /app
# Los ficheros pertenecen a root: el proceso (UID 10001) puede leerlos pero no modificarlos
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

USER 10001:10001

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC -Dnetworkaddress.cache.ttl=30 -Dnetworkaddress.cache.negative.ttl=10"

EXPOSE 8080 8081

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8081/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "opswatch.jar"]
```

| Decisión | Motivo |
|---|---|
| JDK solo en la etapa de build y JRE en la de ejecución | Sin compilador ni Maven en producción: menos superficie y menos tamaño |
| `eclipse-temurin:25-jre-alpine` | Imagen oficial y pequeña. Si Alpine (musl) diera algún problema con una dependencia nativa, se cambia a la variante Ubuntu (`25-jre`). **Se fija por digest** (`@sha256:…`) y Dependabot la actualiza |
| Capas de Spring Boot | Un cambio de código solo invalida la capa `application` (unos KB), no las decenas de MB de dependencias |
| Cache mount de `~/.m2` | Builds repetidos sin volver a descargar dependencias |
| Usuario 10001 sin shell ni home | No root. UID alto para no coincidir con usuarios del host |
| Ficheros propiedad de root | Un proceso comprometido no puede modificar el código de la aplicación |
| `MaxRAMPercentage=75` | La JVM respeta el límite de memoria del contenedor y deja margen para el resto (virtual threads, buffers, metaspace) |
| `ExitOnOutOfMemoryError` | Ante un OOM, el contenedor muere y Docker lo reinicia, en lugar de quedar zombi |
| TTL de DNS de la JVM | Ver el [motor](../architecture/monitoring-engine.md#dns) |
| Healthcheck contra `liveness` en el puerto de management | `wget` viene en Alpine (busybox). No hace falta instalar `curl` |
| Sin `ARG` ni `ENV` con secretos | Quedarían en las capas y en `docker history` |

Tamaño objetivo: menos de 250 MB sin comprimir. El JRE de Alpine ronda los 100 a 150 MB y la aplicación con sus dependencias, unos 60 a 90 MB. Se medirá en el Sprint 0.

Optimizaciones evaluadas y aplazadas: un JRE a medida con `jlink` (imagen más pequeña, pero hay que mantener la lista de módulos del JDK) y la cache AOT del JDK para el arranque (JEP 483 y relacionados). Se valoran si el tamaño o el tiempo de arranque llegan a importar.

## `.dockerignore`

```text
.git
.github
.idea
.vscode
*.iml
target/
docs/
.env
.env.*
!.env.example
secrets/
*.pem
*.key
**/*.log
docker-compose*.yml
compose*.yaml
```

Así no entran en el contexto de build secretos locales, el historial de Git ni ficheros pesados innecesarios.

## `docker-compose.yml` (entorno local, V1)

```yaml
name: opswatch

services:
  postgres:
    image: postgres:18-alpine@sha256:…   # fijado por digest; PostgresTestcontainer lo lee de aquí
    environment:
      POSTGRES_DB: ${POSTGRES_DB:-opswatch}
      POSTGRES_USER: ${POSTGRES_USER:-opswatch}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?define POSTGRES_PASSWORD en .env}
    ports:
      - "127.0.0.1:${POSTGRES_PORT:-5432}:5432"   # solo loopback: accesible desde el IDE, no desde la red
    volumes:
      - postgres-data:/var/lib/postgresql   # PGDATA es /var/lib/postgresql/18/docker desde la imagen 18 (verificado en OW-004)
    healthcheck:
      # Por TCP: el servidor temporal de la inicialización solo escucha en el socket Unix
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U \"$${POSTGRES_USER}\" -d \"$${POSTGRES_DB}\""]
      interval: 5s
      timeout: 3s
      retries: 10

  app:
    profiles: ["app"]                  # solo con --profile app; en el día a día la app corre desde el IDE
    build: .
    image: opswatch:local
    env_file: .env
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-opswatch}
    ports:
      - "127.0.0.1:8080:8080"
    depends_on:
      postgres:
        condition: service_healthy
    read_only: true
    tmpfs:
      - /tmp
    security_opt:
      - no-new-privileges:true
    cap_drop:
      - ALL
    mem_limit: 1g
    cpus: 2

volumes:
  postgres-data:
```

Uso:

```bash
docker compose up -d --wait postgres     # desarrollo diario: la app desde el IDE con el perfil local
docker compose --profile app up --build  # todo en contenedores (OW-009)
docker compose down                      # parar (el volumen de datos se conserva)
docker compose down -v                   # parar y BORRAR los datos locales
```

Las credenciales salen de `.env`, que `scripts/dev-keys.sh` crea con una contraseña aleatoria. Compose lo lee solo, y la aplicación con el perfil `local` lo importa como fuente de propiedades ([entornos](environments.md#env-y-perfil-local)). PostgreSQL fija la contraseña al crear el volumen: cambiarla después en `.env` exige `docker compose down -v`.

Alternativa de comodidad evaluada en OW-004 y **no adoptada**: el soporte de Docker Compose de Spring Boot (`spring-boot-docker-compose`) arranca `postgres` al lanzar la aplicación. Ahorra un comando, pero añade una dependencia que arranca contenedores desde la aplicación y esconde de dónde salen las credenciales. `docker compose up` explícito más `.env` es suficiente.

## Servicios que llegan después

Cada uno entra en su fase, con un profile de Compose para que no arranque si no se pide:

| Servicio | Profile | Fase | Para qué |
|---|---|---|---|
| `mailpit` | `mail` | 4 | Servidor SMTP falso con interfaz web para ver los emails en local |
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
- `restart: unless-stopped`.
- Límites de CPU y memoria acordes con el VPS.
- Logs en JSON con rotación del driver (`max-size`, `max-file`).
- Un proyecto de Compose por entorno (`opswatch-prod` y `opswatch-staging`) con volúmenes y redes separados.

## Escaneo

- Trivy analiza la imagen en CI tras el build. La build falla con vulnerabilidades `CRITICAL` o `HIGH` que tengan corrección disponible ([CI/CD](ci-cd.md)).
- Dependabot vigila las imágenes base fijadas por digest.
