# Modelo de autorización multi-tenant

Estado: diseño inicial · Última revisión: 2026-10-02 · Decisión: [ADR-004](../adr/ADR-004-security-strategy.md)

## 1. Modelo

```text
User  ←→  Membership (role)  ←→  Organization  →  Project  →  Monitor  →  Check / Incident
```

- Un usuario **no tiene roles globales**. Tiene una membresía por organización, cada una con su rol.
- Ejemplo: Ricardo es `OWNER` en la organización A y `VIEWER` en la B. En A puede borrar monitores; en B solo puede leer.
- Todo dato de negocio pertenece a **exactamente una** organización. Los proyectos, monitores, incidentes y canales llevan `organization_id`, directo o a través del proyecto con una FK compuesta que garantiza la coherencia.

## 2. Roles

| Rol | Pensado para |
|---|---|
| `OWNER` | Dueños de la organización: todo, incluida la gestión de otros `OWNER` y `ADMIN` y el borrado de la organización |
| `ADMIN` | Administración diaria: proyectos, canales de notificación, miembros `MEMBER` y `VIEWER` |
| `MEMBER` | Operación: crear y editar monitores, reconocer incidentes |
| `VIEWER` | Solo lectura |

## 3. Permisos

Los roles se traducen a permisos **en código** (un `enum` con un mapa inmutable). No hay roles configurables en V1: sin un caso real, un editor de roles es complejidad sin uso.

```java
public enum Permission {
    ORGANIZATION_READ, ORGANIZATION_UPDATE, ORGANIZATION_DELETE,
    MEMBER_READ, MEMBER_MANAGE_BASIC, MEMBER_MANAGE_PRIVILEGED,
    PROJECT_READ, PROJECT_WRITE,
    MONITOR_READ, MONITOR_WRITE,
    INCIDENT_READ, INCIDENT_ACKNOWLEDGE,
    CHANNEL_READ, CHANNEL_WRITE
}
```

### Matriz RBAC

| Permiso | Qué permite | OWNER | ADMIN | MEMBER | VIEWER |
|---|---|:-:|:-:|:-:|:-:|
| `ORGANIZATION_READ` | Ver la organización | ✓ | ✓ | ✓ | ✓ |
| `ORGANIZATION_UPDATE` | Renombrar | ✓ | ✓ | | |
| `ORGANIZATION_DELETE` | Borrar la organización | ✓ | | | |
| `MEMBER_READ` | Listar miembros y sus roles | ✓ | ✓ | ✓ | ✓ |
| `MEMBER_MANAGE_BASIC` | Añadir, quitar y cambiar entre `MEMBER` y `VIEWER` | ✓ | ✓ | | |
| `MEMBER_MANAGE_PRIVILEGED` | Añadir, quitar o asignar `ADMIN` y `OWNER` | ✓ | | | |
| `PROJECT_READ` | Ver proyectos | ✓ | ✓ | ✓ | ✓ |
| `PROJECT_WRITE` | Crear, renombrar y borrar proyectos | ✓ | ✓ | | |
| `MONITOR_READ` | Ver monitores, estado, checks y estadísticas (**no** los valores de los headers) | ✓ | ✓ | ✓ | ✓ |
| `MONITOR_WRITE` | Crear, editar, pausar, reanudar y borrar monitores | ✓ | ✓ | ✓ | |
| `INCIDENT_READ` | Ver incidentes y su timeline | ✓ | ✓ | ✓ | ✓ |
| `INCIDENT_ACKNOWLEDGE` | Reconocer incidentes | ✓ | ✓ | ✓ | |
| `CHANNEL_READ` | Ver canales, con destinatarios y URL enmascarados | ✓ | ✓ | ✓ | |
| `CHANNEL_WRITE` | Crear, editar, borrar y probar canales, y rotar el secreto | ✓ | ✓ | | |

Nadie puede leer, con ningún rol, los valores de los headers de los monitores ni el secreto de firma de los webhooks después de crearlos: son **de solo escritura**.

### Reglas que la matriz no expresa

| Regla | Aplicación |
|---|---|
| Siempre hay al menos un `OWNER` | Degradar, expulsar o abandonar que deje cero `OWNER` → `409 business-rule-violation`. Se serializa con `SELECT … FOR UPDATE` sobre la organización |
| Un `ADMIN` no puede tocar a un `OWNER` ni a otro `ADMIN` | Requiere `MEMBER_MANAGE_PRIVILEGED` |
| Nadie cambia su propio rol hacia arriba | Un cambio de rol sobre uno mismo solo puede bajar el rol. Bajar no necesita permiso de gestión, igual que abandonar |
| Cualquier miembro puede abandonar la organización | Salvo el último `OWNER` |
| Crear una organización | Cualquier usuario autenticado, hasta `opswatch.limits.organizations-per-user` |

En `MembershipService`, cada cambio de miembros bloquea primero la fila de la organización y después comprueba, en este orden:
1. que quien pide es miembro (`404`);
2. que el miembro afectado existe (`404`);
3. la regla del último `OWNER` (`409`);
4. que nadie se sube el rol (`403`);
5. el permiso según los roles implicados (`403`);
6. `If-Match` (`412`).

La regla del último `OWNER` va antes que el permiso a propósito. Cuando dos `OWNER` se degradan a la vez, el segundo en tomar el bloqueo ya no es `OWNER`, y la respuesta que explica por qué es el `409`. Que se autorice con el bloqueo tomado, y no antes, impide actuar con un rol que otro cambio acaba de quitar.

## 4. Cómo se decide en cada petición

```text
1. Autenticación   → Spring Security valida el JWT → CurrentUser(userId)
2. Carga           → el caso de uso carga el recurso por id (solo los no borrados)
3. Tenant          → organizationId sale del recurso cargado, NUNCA de la petición
4. Membresía       → AccessControl busca (organizationId, userId) en memberships
5. Decisión        → no es miembro → 404 · es miembro sin permiso → 403 · permitido → continúa
```

```java
public interface AccessControl {

    /**
     * Comprueba que el usuario tiene el permiso en la organización.
     * @return su rol en ella, para las respuestas que lo muestran (myRole)
     * @throws ResourceNotFoundException si no es miembro, o la organización no existe o está borrada (404: no se revela que existe)
     * @throws PermissionDeniedException si es miembro pero su rol no incluye el permiso (403)
     */
    Role require(UUID userId, UUID organizationId, Permission permission);

    /**
     * Resuelve el proyecto (no borrado) y comprueba el permiso en su organización (OW-019). El 404 habla del
     * proyecto, nunca de su organización: no revela a quién pertenece.
     * @return el proyecto y su organización, para que quien llama no los tome nunca de la petición
     * @throws ResourceNotFoundException si el proyecto no existe, está borrado o el usuario no es miembro de su organización
     */
    ProjectRef requireForProject(UUID userId, UUID projectId, Permission permission);
}
```

Uso típico en un caso de uso, el de `MonitorService` (OW-021):

```java
/** Autoriza sobre su proyecto, que tampoco puede estar borrado. El 404 habla del monitor, nunca de su proyecto. */
private Monitor authorized(UUID userId, UUID monitorId, Permission permission) {
    Monitor monitor = monitors.findByIdAndDeletedAtIsNull(monitorId)
            .orElseThrow(() -> new ResourceNotFoundException("monitor", monitorId));
    try {
        access.requireForProject(userId, monitor.projectId(), permission);
    } catch (ResourceNotFoundException ex) {
        throw new ResourceNotFoundException("monitor", monitorId);
    }
    return monitor;
}
```

Se autoriza sobre el proyecto y no con `require(monitor.organizationId())` por dos motivos: un monitor de un proyecto ya borrado da `404` aunque la limpieza asíncrona (OW-044) todavía no lo haya borrado, y el `404` de `require` nombraría la organización.

### Por qué `404` y no `403` para quien no es miembro

Con `403`, un atacante podría probar ids y saber cuáles existen en otras organizaciones. Con `404`, desde fuera un recurso ajeno es indistinguible de uno inexistente. Un miembro sin permiso sí recibe `403`: ya sabe que el recurso existe y el mensaje le explica por qué no puede actuar.

### Por qué autorización explícita en el caso de uso y no `@PreAuthorize`

`@PreAuthorize("@access.can(#monitorId, 'MONITOR_WRITE')")` obligaría a cargar el recurso dos veces (una en la expresión y otra en el método) o a esconder la carga dentro de la expresión. La llamada explícita:

- se lee en el mismo sitio que la lógica;
- se prueba sin el contexto de seguridad de Spring;
- reutiliza la entidad ya cargada.

Spring Security sigue protegiendo **la autenticación** de forma declarativa: todo `/api/**` exige un JWT válido salvo la lista pública.

## 5. Prevención de IDOR y aislamiento entre organizaciones

| Medida | Detalle |
|---|---|
| Ids no adivinables | UUIDv7: 74 bits aleatorios. No es la defensa principal, pero dificulta la enumeración |
| El tenant sale del recurso | Nunca se hace `findById(id)` y se confía en un `organizationId` recibido en la URL o el cuerpo |
| Consultas de listado filtradas por tenant | Los repositorios de listados exigen `organizationId` o `projectId` como parámetro obligatorio: `findByProjectIdAndDeletedAtIsNull(projectId, pageable)` |
| FK compuestas | `monitors (project_id, organization_id) → projects (id, organization_id)`: la base de datos impide un monitor cuyo `organization_id` no coincida con el de su proyecto |
| Recursos anidados por el padre | Crear un monitor en `POST /api/v1/projects/{projectId}/monitors` autoriza sobre el proyecto de la ruta. El `projectId` nunca va en el cuerpo |
| Mover recursos entre tenants | No existe en V1. Un monitor no puede cambiar de proyecto |
| Sin roles en el token | Una membresía revocada deja de valer al instante |
| Tests dedicados | Para cada endpoint con id: un usuario de la organización B pide un recurso de A y debe recibir `404`, sin efectos secundarios |

### Row Level Security de PostgreSQL

Se evaluó como defensa adicional: una política `organization_id = current_setting('app.org_id')` con `SET LOCAL` en cada transacción.

**No se adopta en V1:**
- el scheduler y los jobs de retención operan sobre todas las organizaciones, así que necesitarían roles de base de datos distintos o saltarse la política;
- `SET LOCAL` en cada transacción con un pool de conexiones es fácil de olvidar, y si se olvida falla de forma poco visible;
- las medidas de la tabla anterior, con los tests, cubren el riesgo con menos complejidad.

Se reconsidera si aparecen consultas SQL escritas a mano en muchos sitios o si un incidente de aislamiento lo justifica.

## 6. Recursos públicos (Fase 8)

Las páginas de estado se leen **sin autenticación**. Reglas previstas:

- solo se expone lo marcado explícitamente como público (`StatusPageComponent`) y con **nombres públicos** distintos de los internos;
- nunca se exponen URLs, headers, errores detallados ni ids internos;
- endpoints en una ruta separada (`/api/v1/public/status-pages/{slug}`), con rate limiting y cache.

## 7. Evolución

| Cambio futuro | Efecto |
|---|---|
| API keys por organización | Principal distinto (`ApiKeyPrincipal`) con permisos acotados. `AccessControl` gana una variante para principales que no son usuarios |
| Roles personalizados | Tabla `roles` con permisos. Solo si hay una necesidad real |
| Servicios extraídos (Etapa 3) | El servicio valida el JWT con JWKS y resuelve la membresía por consulta a Core o con una proyección local ([evolución](../architecture/evolution.md#comunicación-inicial)) |
| Cache de membresías | Caffeine con TTL corto, o Redis con varias instancias, si la consulta pesa en el p95 ([ADR-009](../adr/ADR-009-redis.md)) |

## 8. Pruebas

- **Matriz parametrizada:** la tabla de la sección 3 se expresa como datos de test (`rol × permiso → esperado`) y se comprueba contra el mapa de producción (`RoleTest`, que también falla si aparece un permiso sin su fila).
- **Matriz de endpoints:** para cada endpoint, peticiones como `OWNER`, `ADMIN`, `MEMBER`, `VIEWER`, un no miembro y un anónimo, comparando con los códigos esperados (`EndpointAuthorizationMatrixIT`, con un test de completitud sobre los endpoints registrados; [cómo añadir filas](../testing/testing-strategy.md#cómo-añadir-un-endpoint-a-la-matriz-de-autorización)).
- **Invariante del último `OWNER`:** dos `OWNER` que se degradan el uno al otro a la vez; al terminar queda al menos uno.
- **IDOR:** cada endpoint con id se prueba con dos organizaciones.
