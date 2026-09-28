# Protección contra SSRF

Estado: diseño inicial · Última revisión: 2026-09-28 · Módulo: `egress` · Decisión relacionada: [ADR-007](../adr/ADR-007-http-client-and-concurrency.md)

## 1. El riesgo

OpsWatch hace peticiones HTTP a URLs que escriben los usuarios: los monitores y, en la Fase 4, los webhooks. Sin controles, cualquier usuario registrado podría usar el servidor como proxy hacia sitios a los que él no llega:

- `http://localhost:8081/actuator/...` (el puerto de management de la propia aplicación);
- `http://postgres:5432` y otros servicios de la red interna de Docker;
- `http://169.254.169.254/latest/meta-data/...` (metadata de AWS, Oracle Cloud y otros), con credenciales de la máquina;
- `http://metadata.google.internal/...`, `http://100.100.100.200/` (Alibaba);
- la red privada del proveedor (`10.0.0.0/8`, …) y el router del host;
- protocolos distintos de HTTP (`file://`, `gopher://`, `ftp://`).

OpsWatch es un caso de **SSRF semi-ciego**: el usuario ve el código de estado, la latencia y el tipo de error, pero no el cuerpo. Aun así eso basta para descubrir servicios internos (escaneo de puertos por diferencia entre timeout y connection refused) y, contra endpoints de metadata que responden a un `GET` simple, para provocar efectos.

## 2. Vectores de ataque considerados

| Vector | Ejemplo |
|---|---|
| IP literal privada | `http://10.0.0.5/`, `http://127.0.0.1:8081/` |
| Codificaciones alternativas de IP | `http://2130706433/`, `http://0x7f000001/`, `http://0177.0.0.1/`, `http://127.1/` |
| IPv6 | `http://[::1]/`, `http://[::ffff:127.0.0.1]/`, `http://[fd00:ec2::254]/` |
| Nombres que resuelven a IP internas | `http://localhost/`, `http://postgres/`, `http://interno.ejemplo.com` apuntando a `10.x` |
| DNS rebinding | El dominio resuelve a una IP pública al validar y a `127.0.0.1` al conectar |
| Varios registros A/AAAA | Uno público y otro privado, esperando que el cliente elija el privado |
| Redirect hacia la red interna | `https://atacante.com/r` responde `302 Location: http://169.254.169.254/` |
| Credenciales en la URL y confusión del parser | `http://127.0.0.1\@atacante.com/`, `http://usuario@10.0.0.1/` |
| Proxy del entorno | Variables `http_proxy` heredadas que desvían el tráfico |
| Esquemas no HTTP | `file:///etc/passwd`, `gopher://`, `jar:`, `ftp://` |
| Headers para metadata | `Metadata-Flavor: Google`, `Authorization: Bearer Oracle`, `X-aws-ec2-metadata-token` |
| Respuestas hostiles | Cuerpos enormes, headers infinitos, goteo lento (slowloris), bombas de compresión, bucles de redirects |
| Abuso contra terceros | Muchos monitores contra una víctima externa (amplificación), escaneo de puertos de hosts públicos |

## 3. Defensa en capas

Ninguna capa sola basta. La validación al guardar, por ejemplo, no detiene el DNS rebinding. Las capas 1 a 5 están en el código de `egress` y de los clientes que lo usan. Las capas 6 a 8 son de infraestructura y operación.

```mermaid
flowchart TB
    input["URL introducida por el usuario"]
    l1["Capa 1: validación al guardar<br/>esquema, host, puerto, sin credenciales,<br/>resolución DNS actual"]
    l2["Capa 2: DNS con fijación de IP al conectar<br/>GuardedDnsResolver"]
    l3["Capa 3: cada redirect se revalida"]
    l4["Capa 4: restricciones de la petición<br/>métodos, headers, sin proxy, sin cookies"]
    l5["Capa 5: restricciones de la respuesta<br/>deadline, límites, sin cuerpo, errores genéricos"]
    l6["Capa 6: red<br/>firewall del host, sin acceso a metadata"]
    l7["Capa 7: límites de abuso<br/>cuotas, intervalo mínimo, límite por host"]
    l8["Capa 8: detección<br/>métrica y log de bloqueos"]
    input --> l1 --> l2 --> l3 --> l4 --> l5
    l6 -.-> l2
    l7 -.-> l1
    l8 -.-> l2
```

### Capa 1: validación al guardar

`TargetPolicy.validate(URI)` se ejecuta al crear o editar un monitor o un webhook. Su función es dar **retroalimentación inmediata** al usuario (`422 target-not-allowed`), pero **no es la barrera definitiva**.

| Regla | Detalle |
|---|---|
| Parser | `java.net.URI` en modo estricto. Si `getHost()` es nulo (hostnames con `_` o formas ambiguas), se rechaza |
| Esquema | Solo `http` y `https`. Los webhooks, solo `https` |
| Credenciales | Se rechaza cualquier `userinfo` (`usuario:clave@`). Las credenciales van en headers cifrados |
| Longitud | Máximo 2048 caracteres |
| Puerto | `80`, `443` o de `1024` a `65535`. Los puertos bajos distintos de 80 y 443 (22, 25, 110…) se rechazan para limitar el uso del motor como escáner o relé SMTP |
| Host IP literal | Solo IPv4 en notación decimal con puntos (cuatro octetos de 0 a 255, sin ceros a la izquierda) o IPv6 entre corchetes. Cualquier otra forma numérica (`2130706433`, `0x7f000001`, `0177.0.0.1`, `127.1`) se rechaza. La IP se clasifica igual que en la capa 2 |
| Host nombre | Gramática de hostname (RFC 1123), con IDN convertido a punycode. Se rechazan `localhost`, `*.localhost`, `*.local`, `*.internal`, `*.home.arpa`, `metadata.google.internal` y nombres de una sola etiqueta (sin punto, como `postgres`) |
| Resolución actual | Se resuelve el nombre y **todas** las IP tienen que pasar el clasificador. Si no resuelve, se admite con una advertencia: el DNS puede no existir todavía, y la capa 2 decidirá en cada check |
| Fragmento | Se descarta |

### Capa 2: resolución DNS con fijación de IP

Es la defensa principal contra el DNS rebinding. Apache HttpClient 5 permite sustituir su `DnsResolver`, y **conecta exactamente a las direcciones que devuelve el resolver**. Si el resolver filtra, no existe una segunda resolución que un atacante pueda manipular entre la comprobación y la conexión.

```java
final class GuardedDnsResolver implements DnsResolver {

    private final DnsResolver delegate = SystemDefaultDnsResolver.INSTANCE;
    private final IpRangeClassifier classifier;

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = delegate.resolve(host);   // también para IP literales
        for (InetAddress address : addresses) {
            if (!classifier.isAllowed(address)) {
                // Si UNA dirección está bloqueada, se rechaza el host entero:
                // no se elige "la buena" de un conjunto mezclado.
                throw new BlockedTargetException(host, classifier.reasonFor(address));
            }
        }
        return addresses;   // el cliente conecta a estas IP y a ninguna otra
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return delegate.resolveCanonicalHostname(host);
    }
}
```

- `BlockedTargetException` extiende `UnknownHostException` para atravesar la API del cliente sin envoltorios, y el motor la clasifica como `TARGET_BLOCKED`.
- Se aplica en **cada conexión**: en cada check, en cada salto de redirect y en cada envío de webhook.
- **Test obligatorio:** comprobar que las URL con IP literal también pasan por `resolve()`. Si una versión futura del cliente se saltara el resolver para las IP literales, este test fallaría y la capa 1 seguiría rechazándolas.
- Hay que verificar que ningún otro camino del cliente resuelve nombres por su cuenta (proxies, rutas precalculadas). Por eso la capa 4 desactiva los proxies.

#### DNS rebinding: por qué funciona la fijación

```mermaid
sequenceDiagram
    autonumber
    participant M as Motor
    participant G as GuardedDnsResolver
    participant DNS as DNS del atacante (TTL 0)
    participant T as Destino

    Note over M,DNS: Sin fijación: se valida con una resolución y se conecta con otra
    M->>DNS: resolve(evil.test) para validar
    DNS-->>M: 203.0.113.10 (pública, pasa)
    M->>DNS: resolve(evil.test) para conectar
    DNS-->>M: 127.0.0.1
    M->>T: conecta a 127.0.0.1 ✗

    Note over M,DNS: Con fijación: una sola resolución, validada, y la conexión usa esa IP
    M->>G: resolve(evil.test)
    G->>DNS: consulta
    DNS-->>G: 127.0.0.1
    G-->>M: BlockedTargetException ✓
```

Si el atacante responde con una IP pública en la resolución del check, el cliente conecta a esa IP pública. No hay una segunda resolución.

#### Rangos bloqueados

El clasificador bloquea todo lo que no sea unicast global enrutable en internet. Se basa en el registro de direcciones de propósito especial de IANA y **no** en los métodos de `InetAddress` (`isSiteLocalAddress` y los demás), que no cubren CGNAT, ULA, NAT64 ni los rangos de documentación.

**IPv4:**

| Rango | Motivo |
|---|---|
| `0.0.0.0/8` | "Esta red". `0.0.0.0` llega a localhost en muchos sistemas |
| `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16` | Privadas (RFC 1918) |
| `100.64.0.0/10` | CGNAT. Incluye `100.100.100.200` (metadata de Alibaba) |
| `127.0.0.0/8` | Loopback |
| `169.254.0.0/16` | Link-local. Incluye `169.254.169.254` (metadata de AWS, GCP, Azure, Oracle y otros) y `169.254.170.2` (credenciales de AWS ECS) |
| `192.0.0.0/24` | Asignaciones de protocolo de IETF |
| `192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24` | Documentación |
| `192.88.99.0/24` | 6to4 relay (obsoleto) |
| `198.18.0.0/15` | Benchmarking |
| `224.0.0.0/4` | Multicast |
| `240.0.0.0/4`, `255.255.255.255/32` | Reservado y broadcast |

**IPv6:**

| Rango | Motivo |
|---|---|
| `::/128`, `::1/128` | No especificada y loopback |
| `::ffff:0:0/96` | IPv4 mapeada: **se extrae la IPv4 y se clasifica** (Java suele convertirla ya a `Inet4Address`) |
| `64:ff9b::/96`, `64:ff9b:1::/48` | NAT64: se extrae la IPv4 incrustada y se clasifica |
| `100::/64` | Descarte |
| `2001::/32` | Teredo |
| `2001:db8::/32` | Documentación |
| `2002::/16` | 6to4: se extrae la IPv4 incrustada y se clasifica |
| `fc00::/7` | Unique local (ULA). Incluye `fd00:ec2::254` (metadata IPv6 de AWS) |
| `fe80::/10` | Link-local |
| `ff00::/8` | Multicast |

La lista vive en código, en un único sitio, con un test por cada rango (la primera y la última dirección de cada uno, y una dirección pública vecina que debe pasar).

### Capa 3: redirects

- Los redirects automáticos del cliente están **desactivados**. El motor los sigue a mano ([motor](../architecture/monitoring-engine.md#7-redirects)).
- Cada `Location` pasa por la capa 1 (esquema, forma del host y puerto) y su conexión por la capa 2. Un `302` hacia `http://169.254.169.254/` termina en `TARGET_BLOCKED`.
- Como mucho 5 saltos, y se detectan los bucles.
- Los headers configurados **no** se reenvían a un origen distinto.
- Un salto de `https` a `http` se permite para los monitores (es un comportamiento real del destino que conviene observar) y se prohíbe para los webhooks.

### Capa 4: restricciones de la petición

| Restricción | Motivo |
|---|---|
| Métodos: `GET` y `HEAD` en los monitores; `POST` solo para webhooks, con cuerpo generado por OpsWatch | Sin cuerpos controlados por el usuario no se pueden atacar endpoints que exigen `PUT`, como IMDSv2 de AWS, ni forjar protocolos |
| Headers prohibidos: `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Upgrade`, `TE`, `Trailer`, `Expect`, `Proxy-*`, `Cookie`, `Metadata-Flavor`, `X-aws-ec2-metadata-token`, `X-aws-ec2-metadata-token-ttl-seconds`, `X-Forwarded-*`, `Forwarded` | Evitar el request smuggling y los headers que exige la metadata cloud. Es defensa en profundidad: la IP ya está bloqueada |
| `Authorization: Bearer Oracle` | Se rechaza de forma explícita: es el header que exige la metadata v2 de Oracle Cloud |
| Nombres y valores sin `CR` ni `LF`, nombres con la gramática de *token* HTTP, 10 headers como máximo y valores de 1024 bytes como máximo | Inyección de headers |
| Sin proxy (no se usan las propiedades del sistema) | Un proxy del entorno saltaría el `DnsResolver` |
| Sin cookies, sin cache de autenticación, sin reintentos automáticos | Cada check está aislado |

### Capa 5: restricciones de la respuesta

| Restricción | Motivo |
|---|---|
| Deadline total por petición (`timeoutMs` más 200 ms) con `cancel()` | Goteo lento |
| Línea máxima de headers de 8 KiB y 100 headers | Agotamiento de memoria |
| El cuerpo no se lee en V1. Con aserciones de contenido, 64 KiB como máximo | Cuerpos enormes |
| Sin `Accept-Encoding` ni descompresión | Bombas de compresión |
| El usuario ve solo el código, la latencia y una `FailureReason` con un texto genérico | Minimiza la información que filtra un SSRF semi-ciego. Nunca se devuelve el cuerpo ni mensajes crudos de excepciones |

### Capa 6: red (infraestructura)

Si todo lo anterior fallara por un bug, la red debería seguir impidiendo lo peor:

- En el VPS, reglas en la cadena `DOCKER-USER` de iptables o nftables que **descartan el tráfico saliente de los contenedores de la aplicación hacia `169.254.169.254`** y hacia las redes privadas del proveedor, con excepción de la red interna de Docker donde vive PostgreSQL.
- PostgreSQL escucha solo en la red interna de Docker. El puerto de management (8081) no se publica.
- En la [Etapa 3](../architecture/evolution.md), el servicio de Monitoring puede vivir en una red que **solo** tenga salida a internet y acceso a su base de datos. Más adelante, un proxy de salida dedicado (tipo Smokescreen) centralizaría la política.

### Capa 7: límites de abuso

- Cuotas por organización (`monitors-per-organization`) e intervalo mínimo de 30 s.
- Rate limit en el registro para frenar la creación masiva de cuentas.
- Fase 5: límite de concurrencia por host de destino en el dispatcher y una métrica de monitores por host para detectar concentraciones.
- User-Agent identificable, con URL de contacto, para que los terceros puedan bloquearnos o avisarnos.

### Capa 8: detección

- Métrica `opswatch_egress_blocked_total{reason}` y alerta si crece de forma anómala.
- Log de seguridad por cada bloqueo: `monitorId`, organización, host y rango bloqueado, sin la query string.
- Un monitor que pasa a `TARGET_BLOCKED` después de haber estado `UP` es una señal de DNS rebinding o de un cambio de infraestructura del usuario, y queda visible en su historial.

## 4. Configuración para pruebas y benchmarks

Los tests de integración y los benchmarks necesitan llegar a destinos en redes privadas (WireMock en `localhost` y el simulador de destinos en la red de Docker). Para eso existe:

```yaml
opswatch:
  egress:
    allowed-private-cidrs: 127.0.0.1/32, 172.18.0.0/16   # SOLO en test y en el perfil de benchmark
```

Salvaguardas:

1. La propiedad está vacía por defecto.
2. Con el perfil `production`, la aplicación **no arranca** si tiene valor ([arquitectura de seguridad](security-architecture.md#13-salvaguardas-de-arranque-en-producción)).
3. Aunque se configure, **nunca** permite `169.254.0.0/16`, `fd00:ec2::/32` ni `100.100.100.200`: la metadata cloud no tiene excepción.
4. Un test comprueba estas tres reglas.

**Producto:** un OpsWatch autoalojado que quiera vigilar servicios internos es un caso de uso legítimo (otras herramientas de monitoreo lo permiten). OpsWatch es SaaS multi-tenant, así que no lo permite. Si algún día se ofrece una edición autoalojada, esta propiedad sería el punto de extensión, con la misma prohibición de la metadata.

## 5. Casos de prueba obligatorios

| # | Entrada | Resultado esperado |
|---|---|---|
| 1 | `http://127.0.0.1/` | Rechazada al guardar |
| 2 | `http://localhost/` | Rechazada al guardar |
| 3 | `http://[::1]/` | Rechazada al guardar |
| 4 | `http://[::ffff:127.0.0.1]/` | Rechazada al guardar |
| 5 | `http://2130706433/`, `http://0x7f000001/`, `http://0177.0.0.1/`, `http://127.1/` | Rechazadas (forma de host no admitida) |
| 6 | `http://0.0.0.0/` | Rechazada |
| 7 | `http://169.254.169.254/latest/meta-data/` | Rechazada |
| 8 | `http://metadata.google.internal/` | Rechazada |
| 9 | `http://100.100.100.200/` | Rechazada |
| 10 | `http://[fd00:ec2::254]/` | Rechazada |
| 11 | `http://10.0.0.1/`, `http://172.16.5.4/`, `http://192.168.1.1/` | Rechazadas |
| 12 | `http://postgres:5432/` | Rechazada (nombre de una sola etiqueta y puerto) |
| 13 | `file:///etc/passwd`, `gopher://x`, `ftp://x` | Rechazadas (esquema) |
| 14 | `http://usuario:clave@example.com/` | Rechazada (credenciales) |
| 15 | `http://example.com:22/` | Rechazada (puerto) |
| 16 | Nombre que resuelve a una IP pública y otra privada | Check `TARGET_BLOCKED` |
| 17 | Resolver falso: pública al guardar, `127.0.0.1` en el check | Check `TARGET_BLOCKED` (rebinding) |
| 18 | Destino permitido que responde `302 Location: http://169.254.169.254/` | Check `TARGET_BLOCKED` en el salto |
| 19 | Redirect a otro origen con `Authorization` configurado | El segundo salto no lleva `Authorization` |
| 20 | Header `Metadata-Flavor: Google` o `Authorization: Bearer Oracle` | Rechazado al guardar |
| 21 | Header con `\r\n` | Rechazado al guardar |
| 22 | Destino que gotea un byte por segundo | Check `TIMEOUT` en `timeoutMs` más un margen pequeño |
| 23 | Destino con 10 000 headers | Check `PROTOCOL_ERROR` |
| 24 | `allowed-private-cidrs` configurado con el perfil `production` | La aplicación no arranca |
| 25 | `allowed-private-cidrs=0.0.0.0/0` con el perfil `test` y destino `169.254.169.254` | Sigue bloqueado |
| 26 | Webhook `http://` (no `https`) | Rechazado al guardar |

## 6. Webhooks

Los webhooks reutilizan exactamente la misma política a través de `EgressHttpClients`, con estas diferencias:

- solo `https`, en la URL y en los redirects;
- `POST` con cuerpo JSON generado por OpsWatch, nunca por el usuario;
- timeout fijo de 5 s;
- firma `X-OpsWatch-Signature: t=<timestamp>,v1=<HMAC-SHA256(secret, t + "." + body)>` para que el receptor verifique el origen y descarte las repeticiones antiguas.
