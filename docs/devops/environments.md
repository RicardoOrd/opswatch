# Entornos, configuración y secretos

Estado: diseño inicial · Última revisión: 2026-09-29

## Perfiles

| Aspecto | `local` | `test` | `staging` | `production` |
|---|---|---|---|---|
| Uso | Desarrollo en la máquina | Tests automáticos | Preproducción pública en el VPS | Producción |
| Base de datos | PostgreSQL de Docker Compose | Testcontainers | PostgreSQL propio en el VPS | PostgreSQL propio en el VPS |
| Formato de logs | Texto legible con colores | Texto | JSON (ECS) | JSON (ECS) |
| Nivel de log de `io.github.ricardoord.opswatch` | `DEBUG` | `INFO` | `INFO` | `INFO` |
| Swagger UI | Sí | No | Sí | No |
| CORS | `http://localhost:5173` (frontend futuro) | — | Origen de staging | Vacío (mismo origen) |
| Fuente de los secretos | `.env` | Generados en el test | Docker secrets | Docker secrets |
| Claves JWT y de cifrado | De desarrollo, generadas por un script local | Generadas por test | Propias de staging | Propias de producción, **distintas** de staging |
| `egress.allowed-private-cidrs` | Vacío (o la red local si se prueba con un destino propio) | Loopback y la red de Testcontainers | **Vacío** | **Vacío. Obligatorio: la aplicación no arranca si no** |
| Email | Mailpit | `EmailSender` falso | SMTP del proveedor (sandbox si existe) | SMTP del proveedor |
| bcrypt | Coste 12 | Coste 4 (velocidad de los tests) | Coste 12 | Coste 12 |
| Salvaguardas de arranque | Desactivadas | Desactivadas | Activadas | Activadas |

Los perfiles se activan con `SPRING_PROFILES_ACTIVE`. `staging` y `production` comparten casi toda la configuración: un fichero `application-deployed.yml` común y ficheros específicos con solo las diferencias, para que staging se parezca de verdad a producción.

## Dónde vive cada tipo de configuración

| Tipo | Dónde | Ejemplo |
|---|---|---|
| Valores por defecto seguros | `application.yml` (en Git) | Timeouts, límites, TTL |
| Diferencias por perfil | `application-<perfil>.yml` (en Git) | Formato de logs, Swagger |
| Datos del entorno sin secreto | Variables de entorno | URL de la base de datos, orígenes CORS, host SMTP |
| **Secretos** | `.env` en local (fuera de Git). **Docker secrets** en staging y producción | Contraseña de la base de datos, claves JWT y de cifrado, credenciales SMTP |

**Regla:** ni `application.yml` ni ningún fichero de Git contienen un secreto, **ni siquiera de desarrollo**. Los secretos de desarrollo se generan en local con `scripts/dev-keys.sh` y se guardan en `.env` y en `secrets/`, que están en `.gitignore`.

### Lectura de secretos en producción

```yaml
spring:
  config:
    import: "optional:configtree:/run/secrets/"
```

Cada fichero en `/run/secrets/` se convierte en una propiedad con el nombre del fichero. Por ejemplo, `/run/secrets/opswatch.security.jwt.private-key` pasa a ser `opswatch.security.jwt.private-key`. Ventajas frente a las variables de entorno: no aparecen en `docker inspect`, en `/proc/<pid>/environ` ni en volcados de entorno, y pueden tener permisos de fichero (0400).

### `.env` y perfil `local`

`.env.example` se versiona y documenta **qué** hay que definir, sin valores reales. `scripts/dev-keys.sh` crea `.env` a partir de él con una contraseña aleatoria:

```dotenv
POSTGRES_DB=opswatch
POSTGRES_USER=opswatch
POSTGRES_PASSWORD=change-me
POSTGRES_PORT=5432
```

Un solo juego de variables sirve a los dos lados:

- **Docker Compose** lee `.env` por sí mismo y crea la base de datos con esas credenciales.
- **La aplicación con el perfil `local`** lo importa como fuente de propiedades (`spring.config.import: optional:file:.env[.properties]`) y construye `spring.datasource.*` con `${POSTGRES_…}`. Así la contraseña no se duplica en variables `SPRING_DATASOURCE_*` que podrían desincronizarse.

Decisiones de OW-004:

- Las claves de un fichero importado **no** pasan por el *relaxed binding* de las variables de entorno: `SPRING_DATASOURCE_PASSWORD` en `.env` no se convierte en `spring.datasource.password`. Por eso `application-local.yml` mapea cada variable de forma explícita, y así lo harán las propiedades que lleguen después.
- La importación es opcional porque las variables también pueden llegar del entorno (el servicio `app` de Compose, OW-009), que tiene prioridad sobre el fichero. La contrapartida: sin `.env`, el placeholder queda sin resolver y el arranque falla con `password authentication failed`, no con un mensaje sobre `.env`. El README lo explica.
- Las claves JWT y de cifrado de desarrollo **no** van en `.env`: `dev-keys.sh` las deja en `secrets/` (`jwt-dev-private.pem`, `jwt-dev-public.pem` y `encryption-dev-key`). OW-013 y OW-022 las conectan a sus propiedades.

### Gestor de secretos (futuro)

Con varios hosts o servicios, los ficheros de Docker secrets gestionados a mano dejan de escalar. Opciones: el vault del proveedor cloud, HashiCorp Vault o Infisical. Es una [decisión abierta](../architecture/open-decisions.md) hasta la Etapa 3.

## Catálogo de propiedades

**Fuente única de los valores configurables.** Si otro documento cita un valor distinto, manda este.

Los rangos de validación del dominio (intervalo de 30 a 3600 s, timeout de 1 a 30 s, umbrales de 1 a 10) **no** son configurables: son reglas del producto y están en el código y en los `CHECK` de la base de datos ([modelo de dominio](../architecture/domain-model.md#monitor)).

### Seguridad

| Propiedad | Por defecto | Notas |
|---|---|---|
| `opswatch.security.jwt.issuer` | — (obligatoria) | URL pública de la API |
| `opswatch.security.jwt.audience` | `opswatch-api` | |
| `opswatch.security.jwt.access-token-ttl` | `15m` | |
| `opswatch.security.jwt.private-key` | — (**secreto**) | PEM RSA de 2048 bits como mínimo |
| `opswatch.security.jwt.public-key` | — | PEM. Durante una rotación, lista con la anterior |
| `opswatch.security.jwt.key-id` | — | `kid` del header |
| `opswatch.security.refresh-token.ttl` | `14d` | |
| `opswatch.security.refresh-token.family-max-ttl` | `30d` | |
| `opswatch.security.refresh-token.cookie-name` | `opswatch_refresh` | |
| `opswatch.security.password.bcrypt-strength` | `12` | `4` en `test` |
| `opswatch.security.encryption.keys.<id>` | — (**secreto**) | AES-256 en Base64. Varias para rotar |
| `opswatch.security.encryption.active-key-id` | — | Clave con la que se cifra lo nuevo |
| `opswatch.security.cors.allowed-origins` | vacío | Nunca `*` |
| `opswatch.security.rate-limit.login-per-ip` | `10/1m` | |
| `opswatch.security.rate-limit.login-per-email` | `5/1m` | |
| `opswatch.security.rate-limit.register-per-ip` | `5/1h` | |
| `opswatch.security.rate-limit.refresh-per-ip` | `30/1m` | |
| `opswatch.security.rate-limit.password-change-per-user` | `5/15m` | Contra adivinar la contraseña actual con un access token robado |

### Cuotas

| Propiedad | Por defecto |
|---|---|
| `opswatch.limits.organizations-per-user` | `5` |
| `opswatch.limits.members-per-organization` | `50` |
| `opswatch.limits.projects-per-organization` | `20` |
| `opswatch.limits.monitors-per-organization` | `50` |
| `opswatch.limits.channels-per-organization` | `10` |
| `opswatch.limits.recipients-per-channel` | `10` |

### Motor de monitoreo

| Propiedad | Por defecto | Notas |
|---|---|---|
| `opswatch.monitoring.engine.enabled` | `true` | `false` en instancias que solo sirven la API |
| `opswatch.monitoring.engine.dispatch-interval` | `1s` | |
| `opswatch.monitoring.engine.max-concurrent-checks` | `200` | Tamaño del semáforo y del pool de conexiones HTTP |
| `opswatch.monitoring.engine.max-batch-size` | `500` | Máximo de monitores reclamados por ciclo |
| `opswatch.monitoring.engine.max-redirects` | `5` | |
| `opswatch.monitoring.engine.deadline-grace` | `200ms` | Margen sobre `timeoutMs` para cortar la petición |
| `opswatch.monitoring.engine.overdue-threshold` | `5s` | Para la métrica de vencidos |
| `opswatch.monitoring.engine.shutdown-grace` | `5s` | Espera extra en el apagado |
| `opswatch.monitoring.engine.user-agent` | `OpsWatch-Monitor/${version} (+<url de ayuda>)` | |

### Egress

| Propiedad | Por defecto | Notas |
|---|---|---|
| `opswatch.egress.allowed-private-cidrs` | vacío | **Prohibida en `production`.** Nunca abre la metadata cloud ([SSRF](../security/ssrf-protection.md#4-configuración-para-pruebas-y-benchmarks)) |
| `opswatch.egress.max-response-header-line` | `8KB` | |
| `opswatch.egress.max-response-headers` | `100` | |

### Notificaciones

| Propiedad | Por defecto |
|---|---|
| `opswatch.notification.delivery.poll-interval` | `5s` |
| `opswatch.notification.delivery.batch-size` | `50` |
| `opswatch.notification.delivery.max-attempts` | `6` |
| `opswatch.notification.delivery.backoff` | `0s,30s,2m,10m,30m,1h` |
| `opswatch.notification.webhook.timeout` | `5s` |
| `opswatch.notification.test.rate-limit` | `5/1m` por canal |
| `spring.mail.*` | Según el entorno (host, puerto, usuario, contraseña **secreta**, STARTTLS) |

### Retención

| Propiedad | Por defecto |
|---|---|
| `opswatch.retention.checks` | `30d` |
| `opswatch.retention.deliveries` | `90d` |
| `opswatch.retention.refresh-tokens-grace` | `7d` |
| `opswatch.retention.event-publications` | `7d` |
| `opswatch.retention.batch-size` | `10000` |
| `opswatch.retention.cron` | `0 30 3 * * *` (03:30 UTC) |

### API

| Propiedad | Por defecto |
|---|---|
| `opswatch.api.problem-base-uri` | URL de `docs/api/api-guidelines.md` en el repositorio |
| `opswatch.api.docs-public` | `false` | Permite Swagger UI en `production` de forma explícita |
| `opswatch.api.default-page-size` | `20` |
| `opswatch.api.max-page-size` | `100` |

### Spring y librerías (valores fijados)

| Propiedad | Valor | Motivo |
|---|---|---|
| `spring.jpa.open-in-view` | `false` | Ninguna conexión retenida fuera de las transacciones |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Solo Flyway cambia el esquema |
| `spring.jpa.properties.hibernate.jdbc.time_zone` | `UTC` | |
| `spring.flyway.clean-disabled` | `true` | |
| `spring.jackson.deserialization.fail-on-unknown-properties` | `true` | Evita el mass assignment silencioso |
| `spring.web.locale` / `spring.web.locale-resolver` | `en` / `fixed` | Los mensajes de la API (incluidos los de validación) salen siempre en inglés, sea cual sea el idioma del servidor o del cliente |
| `spring.mvc.problemdetails.enabled` | No se usa | `ProblemDetailsHandler` sustituye al manejador de Problem Details de Spring Boot, que se desactiva solo al existir otro `ResponseEntityExceptionHandler` |
| `server.shutdown` | `graceful` | |
| `spring.lifecycle.timeout-per-shutdown-phase` | `35s` | |
| `server.forward-headers-strategy` | `framework` | Solo con proxies de confianza |
| `management.server.port` | `8081` | Separado de la API |
| `management.endpoints.web.exposure.include` | `health,info,prometheus` | Nada más |
| `management.endpoint.health.probes.enabled` | `true` | `liveness` y `readiness` |
| `spring.modulith.events.republish-outstanding-events-on-restart` | `true` | |
| `spring.threads.virtual.enabled` | `true` | Virtual threads para Tomcat, `@Async` y `@Scheduled` (decidido en OW-006). Con Java 25 no hay pinning por `synchronized` y el modelo coincide con el del motor. Una petición bloqueada espera una conexión del pool de Hikari en lugar de agotar hilos, y esa espera se ve en `hikaricp_connections_pending` |

## Salvaguardas de arranque

Con el perfil `staging` o `production`, la aplicación falla al arrancar si:

- falta cualquier propiedad marcada como secreto u obligatoria;
- `opswatch.egress.allowed-private-cidrs` no está vacío (solo en `production`);
- `opswatch.security.cors.allowed-origins` contiene `*`;
- la clave JWT o la de cifrado coinciden con una clave de desarrollo conocida (huella comprobada);
- `spring.jpa.hibernate.ddl-auto` no es `validate` ni `none`;
- `spring.flyway.clean-disabled` es `false`;
- `opswatch.security.password.bcrypt-strength` es menor que 12 (el coste 4 del perfil `test` nunca llega a un despliegue);
- Swagger UI está activado en `production` sin la propiedad explícita que lo permite (`opswatch.api.docs-public=true`).

Cada regla tiene su test ([testing](../testing/testing-strategy.md#pruebas-de-seguridad)).
