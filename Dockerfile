# syntax=docker/dockerfile:1
# Imagen de la aplicación (docs/devops/docker.md). Imágenes base fijadas por digest; Dependabot las actualiza.

# ---------- build ----------
FROM eclipse-temurin:25-jdk-alpine@sha256:1e20ead9eec7234db293f1913438090d533ed47c0b9e12dc8c7627a802a44ed6 AS build
WORKDIR /workspace

# Primero las dependencias: esta capa se cachea mientras el pom no cambie
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/main/ src/main/
# Los tests corren en el job build de CI; aquí solo se compila y se empaqueta
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -Dmaven.test.skip=true

# Capas de Spring Boot: un cambio de código solo invalida la capa application
RUN java -Djarmode=tools -jar target/opswatch.jar extract --layers --destination target/extracted

# ---------- runtime ----------
FROM eclipse-temurin:25-jre-alpine@sha256:b7528962e65d43064d8a8b67ba175367896bc948363ea2d3fcd7f10d34e1f048 AS runtime

RUN addgroup -S -g 10001 opswatch && adduser -S -u 10001 -G opswatch -H -s /sbin/nologin opswatch

WORKDIR /app
# Los ficheros pertenecen a root: el proceso (UID 10001) puede leerlos pero no modificarlos
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

USER 10001:10001

# La cache DNS de la JVM ya es la del diseño (30 s, 10 s para los fallos): son los valores por defecto del JDK 25
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"

EXPOSE 8080 8081

HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8081/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "opswatch.jar"]
