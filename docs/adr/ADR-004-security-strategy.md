# ADR-004: Estrategia de autenticación y autorización

- **Estado:** Aceptado
- **Fecha:** 2026-09-28
- **Relacionado:** [Arquitectura de seguridad](../security/security-architecture.md) · [Modelo de autorización](../security/authorization-model.md) · [Threat model](../security/threat-model.md)

## 1. ¿Qué problema existe?

Hay que autenticar a los usuarios de una API REST y autorizar cada acción dentro de un modelo multi-tenant, donde un mismo usuario tiene roles distintos en organizaciones distintas. La solución debe servir para V1 (un proceso) y no bloquear la Etapa 3 (varios servicios que validan la identidad).

## 2. ¿Cuáles son los requisitos?

- Credenciales seguras (hash robusto, protección contra la fuerza bruta).
- Sesiones revocables (logout, cambio de contraseña, robo detectado).
- Los cambios de rol y las expulsiones tienen efecto inmediato.
- Aislamiento estricto entre organizaciones.
- Mínimo código de seguridad escrito a mano: se usan los mecanismos estándar de Spring Security.
- Posibilidad de validar la identidad en otros servicios sin compartir secretos que permitan emitir tokens.
- Costo operativo bajo (sin servicios adicionales en V1).

## 3. ¿Qué alternativas tenemos?

**Autenticación:**
- A1. Sesión en el servidor (cookie de sesión y Spring Session JDBC).
- A2. **JWT de acceso de corta duración emitido por la aplicación, más un refresh token opaco rotado.**
- A3. Proveedor de identidad externo (Keycloak autoalojado, Auth0 u otro SaaS).
- A4. Spring Authorization Server embebido o como servicio aparte.

**Autorización:**
- Z1. Roles globales del usuario.
- Z2. **RBAC por membresía (rol por organización), evaluado en el servidor en cada petición.**
- Z3. Roles de la organización dentro del JWT.
- Z4. Motor de políticas (OPA, ABAC).
- Z5. Row Level Security de PostgreSQL como mecanismo principal.

## 4 y 5. Ventajas y desventajas

| Alternativa | Ventajas | Desventajas |
|---|---|---|
| A1 Sesión | Revocación inmediata. Sin tokens en el cliente. Muy seguro con cookies `HttpOnly` | Estado en el servidor (Spring Session en la base de datos). CSRF en toda la API. Validar en otros servicios exige compartir el almacén de sesiones o un gateway |
| **A2 JWT + refresh** | Validación sin estado con el Resource Server estándar. Otros servicios validan con la clave pública (JWKS). Cómodo en Swagger y curl | Revocación del access token no inmediata (acotada por el TTL). Hay que gestionar la rotación del refresh y las claves |
| A3 IdP externo | Funcionalidades completas (MFA, SSO, social). Menos código propio | Keycloak consume cientos de MB de RAM y hay que operarlo. Los SaaS añaden dependencia y límites de sus planes. Oculta justo lo que el proyecto quiere mostrar |
| A4 Authorization Server | Estándar OAuth2 completo | Sobredimensionado para un solo cliente propio |
| Z1 Roles globales | Simple | **No sirve para multi-tenant** |
| **Z2 RBAC por membresía** | Correcto para multi-tenant. Revocación inmediata. Explícito y fácil de probar | Una consulta por petición autorizada |
| Z3 Roles en el JWT | Sin consulta | Roles obsoletos durante todo el TTL. Tokens que crecen con el número de organizaciones |
| Z4 OPA / ABAC | Muy expresivo | Complejidad sin reglas que la necesiten |
| Z5 RLS | Defensa en la base de datos | Jobs que operan sobre todos los tenants. `SET LOCAL` fácil de olvidar con un pool de conexiones ([detalle](../security/authorization-model.md#row-level-security-de-postgresql)) |

## 6. ¿Qué elegimos?

- **A2:** access token JWT **RS256** de **15 minutos** emitido con `JwtEncoder` y validado por **Spring Security OAuth2 Resource Server**, más un refresh token **opaco** (32 bytes aleatorios, guardado como SHA-256) en una cookie `HttpOnly; Secure; SameSite=Strict`, **rotado** en cada uso y con **detección de reutilización** por familia.
- **Contraseñas con bcrypt** (coste 12) a través de `DelegatingPasswordEncoder`.
- **Z2:** RBAC por membresía (`OWNER`, `ADMIN`, `MEMBER`, `VIEWER`) traducido a permisos en código y evaluado por `AccessControl` después de cargar cada recurso. **Sin roles en el JWT.** `404` a quien no es miembro y `403` al miembro sin permiso.

## 7. ¿Por qué?

- El Resource Server estándar evita escribir un filtro JWT propio, que es fuente habitual de fallos.
- La firma asimétrica deja la Etapa 3 resuelta: los servicios validan con la clave pública sin poder emitir tokens.
- Sin roles en el token, el problema de revocación de los JWT se limita a la **autenticación** (15 minutos como máximo) y no afecta a la **autorización** (inmediata).
- El refresh opaco en la base de datos permite revocar sesiones de verdad (logout, cambio de contraseña, robo).
- bcrypt no añade dependencias. `DelegatingPasswordEncoder` permite migrar a Argon2id sin forzar el cambio de contraseñas.

**Compromiso reconocido:** la recomendación actual para aplicaciones en el navegador favorece el patrón BFF (tokens solo en el servidor y una cookie de sesión en el navegador). El diseño elegido es un término medio: access token corto en memoria y refresh `HttpOnly` que rota. Si se introduce un gateway (Etapa 3), se evalúa pasar a BFF.

## 8. ¿Qué costo o complejidad introduce?

- Lógica de rotación y reutilización del refresh token, con sus carreras (se resuelve con `FOR UPDATE` y tests concurrentes).
- Gestión de claves: par RSA como secreto y rotación con `kid`.
- Una consulta de membresía por petición autorizada. Se cachea si se mide que pesa ([ADR-009](ADR-009-redis.md)).
- El frontend y la API tienen que servirse desde el mismo sitio para la cookie `SameSite=Strict`.
- Un access token robado vale hasta 15 minutos.

## 9. ¿Cómo comprobaremos que funciona?

- Tests del JWT: firma alterada, `alg: none`, confusión HS256/RS256, caducidad, `iss` y `aud`.
- Tests del refresh: rotación, reutilización (familia revocada), refresh concurrente y atributos de la cookie.
- Matriz de autorización endpoint × rol, más IDOR entre dos organizaciones en cada endpoint.
- Rate limiting de la autenticación.
- Fase 5: escaneo ZAP baseline y CodeQL sin hallazgos altos.
- Métrica: latencia p95 de la consulta de membresía (Fase 7).

## 10. ¿Qué tendría que pasar para reconsiderarla?

- Necesidad de SSO, MFA o login social: login social con Spring Security OAuth2 Login, o un IdP externo.
- Introducción de un gateway con BFF.
- Necesidad de revocar el access token de inmediato (por ejemplo, por cumplimiento normativo): lista de revocación por `jti` o TTL más corto.
- La consulta de membresía se vuelve un cuello de botella medible: cache (Caffeine o Redis) o un JWT con claims de organización y TTL muy corto.
