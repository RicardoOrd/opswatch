# Migraciones con Flyway

Estado: diseño inicial · Última revisión: 2026-09-28

## Ubicación y nombres

```text
src/main/resources/db/migration/
├── V1__identity_create_users.sql                                   (OW-012)
├── V2__identity_create_refresh_tokens.sql                          (OW-014)
├── V3__organization_create_organizations_and_memberships.sql       (OW-016)
├── V4__modulith_create_event_publication.sql                       (OW-034)
├── V5__organization_create_projects.sql                            (OW-019)
├── V6__monitoring_create_monitors_and_state.sql                    (OW-021)
├── V7__monitoring_add_monitor_request_headers.sql                  (OW-022)
├── V8__monitoring_create_monitor_checks.sql                        (OW-027, prevista)
├── V9__incident_create_incidents_and_timeline.sql                  (OW-032, prevista)
└── V10__notification_create_channels_and_deliveries.sql            (OW-035, prevista)
```

Hasta V7 son las migraciones reales, en el orden en que se aplicaron. Las demás son la secuencia prevista para las Fases 3 y 4: **los números reales se asignan al implementar cada una**, y por eso las issues nombran la migración sin número.

- Formato: `V<n>__<módulo>_<descripción_en_snake_case>.sql`.
- `<n>` es un entero secuencial global. El módulo en el nombre indica quién es el dueño de la migración sin necesidad de abrirla.
- Scripts repetibles (`R__<descripción>.sql`) solo para vistas o funciones que se recrean enteras. No hay ninguno previsto en V1.
- **Colisiones de número entre ramas:** con una sola persona desarrollando son raras. CI comprueba que no haya números duplicados en la rama con respecto a `main`, y si hay colisión, se renumera en la rama antes del merge. Si el proyecto tuviera varias personas, se pasaría a versiones basadas en fecha (`V20260928_1030__…`).

## Configuración

| Propiedad | Valor | Por qué |
|---|---|---|
| `spring.flyway.enabled` | `true` | Las migraciones se aplican al arrancar, antes de aceptar tráfico |
| `spring.flyway.clean-disabled` | `true` **en todos los perfiles** | `clean` borra el esquema entero. Un error de configuración no puede costar la base de datos de producción |
| `spring.flyway.validate-on-migrate` | `true` | Detecta migraciones aplicadas que se editaron |
| `spring.flyway.out-of-order` | `false` | El orden es estricto |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Hibernate **nunca** modifica el esquema: solo comprueba que las entidades coinciden con él |

Desde Flyway 10, el soporte de PostgreSQL es un módulo aparte (`flyway-database-postgresql`) que hay que declarar como dependencia.

## Reglas

1. **Una migración aplicada no se edita nunca.** Para corregirla se escribe otra. El checksum de Flyway lo hace cumplir.
2. **Una migración, un cambio lógico.** Es más fácil de revisar y de diagnosticar si falla.
3. **Siempre hacia delante.** No hay migraciones "down": revertir un cambio de esquema en producción se hace con otra migración. Las migraciones "down" rara vez se prueban y dan una falsa sensación de seguridad.
4. **Compatibles con la versión anterior de la aplicación** (expand / contract), para que desplegar y revertir la aplicación no dependa de revertir la base de datos.
5. **Tiempos de bloqueo acotados.** Las migraciones que alteran tablas con datos empiezan con `SET lock_timeout = '5s';`. Si no consiguen el bloqueo, fallan en lugar de dejar la tabla bloqueada detrás de una cola de consultas.
6. **Índices sobre tablas grandes con `CONCURRENTLY`**, que no admite transacción. Van en una migración propia con el fichero de configuración de script de Flyway (`V10__….sql.conf` con `executeInTransaction=false`).
7. **Migraciones de datos en lotes** cuando afectan a muchas filas, y nunca mezcladas con cambios de esquema en la misma migración.
8. **Toda migración se prueba** en CI desde una base de datos vacía (Testcontainers) con `ddl-auto=validate`. Desde la Fase 6, también antes de desplegar, contra una copia restaurada de staging.

## Cambios compatibles hacia atrás: expand / contract

Ejemplo: renombrar `monitors.url` a `monitors.target_url`.

| Paso | Release | Esquema | Aplicación |
|---|---|---|---|
| 1. Expand | N | Añadir `target_url` (nullable) | Escribe en las dos columnas y lee `url` |
| 2. Migrar | N | Rellenar `target_url` desde `url` en lotes | — |
| 3. Cambiar la lectura | N+1 | `target_url` pasa a `NOT NULL` | Lee `target_url` y sigue escribiendo en las dos |
| 4. Contract | N+2 | Borrar `url` | Solo `target_url` |

En cada paso, la versión anterior de la aplicación sigue funcionando con el esquema nuevo, así que un rollback de la aplicación no necesita tocar la base de datos.

| Cambio | ¿Compatible en un paso? |
|---|---|
| Añadir una tabla, o una columna nullable o con `DEFAULT` | Sí |
| Añadir un índice (con `CONCURRENTLY` si la tabla es grande) | Sí |
| Ampliar un `CHECK` (un valor nuevo en un enum) | Sí, **si la aplicación anterior tolera leer el valor nuevo**. Los enums de Java fallan con valores desconocidos: se despliega primero la aplicación que los conoce |
| Renombrar una columna o tabla | No: expand / contract |
| Añadir `NOT NULL` a una columna existente | No: primero rellenar, después la restricción (`NOT VALID` + `VALIDATE CONSTRAINT` en tablas grandes) |
| Borrar una columna | Solo cuando ninguna versión desplegada la lea (paso contract) |
| Restringir un `CHECK` | No: primero limpiar los datos y después la restricción |

## Microservicios

Cuando un módulo se extraiga ([evolución](../architecture/evolution.md#estrategia-de-datos)):

1. **Cada servicio es dueño de su esquema y de su historial de Flyway.** Ningún servicio migra tablas de otro.
2. Las tablas del módulo pasan primero a su propio esquema (`monitoring`) dentro de la misma base de datos, con una migración en el monolito.
3. El servicio nuevo arranca su historial con `baselineVersion` sobre el esquema existente (`spring.flyway.baseline-on-migrate=true` **solo** en ese primer despliegue) y sus migraciones empiezan en la versión siguiente.
4. Las migraciones antiguas del monolito que crearon esas tablas se quedan en el historial del monolito como registro, sin reescribirse.
5. Las FK entre servicios se eliminan con una migración del lado que las tenía ([lista](database-design.md#8-claves-foráneas-entre-módulos)).
6. Los contratos entre servicios (API y eventos) se versionan aparte del esquema: un cambio de esquema interno no debe obligar a otro servicio a desplegarse.

## Checklist de revisión de una migración

- [ ] Nombre con el formato correcto y número sin colisión con `main`.
- [ ] Compatible con la versión desplegada de la aplicación, o parte documentada de un expand / contract.
- [ ] `lock_timeout` si toca tablas con datos.
- [ ] Índices en tablas grandes con `CONCURRENTLY` y en una migración no transaccional.
- [ ] Restricciones con nombre según la convención.
- [ ] El test de arranque con `ddl-auto=validate` pasa.
- [ ] Diseño de base de datos o modelo de dominio actualizados si cambió el modelo.
