# Webhooks: guía para receptores

Un canal `WEBHOOK` recibe un `POST` por cada incidente que se abre o se resuelve en los proyectos que cubre, y uno por cada prueba del canal. Esta guía explica qué llega y cómo comprobar que lo envió OpsWatch (OW-043).

## Qué llega

```http
POST /tu/ruta HTTP/1.1
Content-Type: application/json
User-Agent: OpsWatch-Webhook/0.4.0 (+https://github.com/RicardoOrd/opswatch)
X-OpsWatch-Webhook-Version: 1
X-OpsWatch-Signature: t=1791453600,v1=<HMAC-SHA256 en hexadecimal, 64 caracteres>
```

```json
{
  "id": "0192f0c2-…",
  "type": "INCIDENT_OPENED",
  "occurredAt": "2026-10-08T10:03:00Z",
  "incident": {
    "id": "0192…", "status": "OPEN", "monitorId": "0192…", "monitorName": "Payments API",
    "projectId": "0192…", "cause": "TIMEOUT", "openedAt": "2026-10-08T10:03:00Z"
  }
}
```

| Campo | Qué es |
|---|---|
| `id` | El id de la **entrega**. Es el mismo en cada reintento: úsalo para descartar duplicados |
| `type` | `INCIDENT_OPENED`, `INCIDENT_RESOLVED` o `TEST` |
| `occurredAt` | Cuándo pasó: la apertura, la resolución o la petición de la prueba |
| `incident` | `null` en una prueba. El incidente **tal como está al enviar**: un aviso de apertura que se reintenta después de la resolución ya llega con `status: "RESOLVED"` |
| `incident.status` | `OPEN`, `ACKNOWLEDGED` o `RESOLVED` |
| `incident.cause` | La causa del check que lo abrió (`TIMEOUT`, `UNEXPECTED_STATUS`, `CONNECTION_FAILED`…) |
| `incident.httpStatus` | El código de ese check. No aparece si no hubo respuesta |
| `incident.resolvedAt`, `incident.resolution` | Solo una vez resuelto. `resolution` es `AUTO_RECOVERED`, `MONITOR_PAUSED` o `MONITOR_DELETED` |

El contrato es la versión `1` (`X-OpsWatch-Webhook-Version`). Pueden aparecer campos nuevos sin cambiar de versión: ignora los que no conozcas ([versionado](../development/versioning.md#webhooks)).

## Cómo verificar la firma

El secreto del canal (`whsec_…`) se muestra **una sola vez**, en la respuesta que crea el canal o rota el secreto. Guárdalo en tu receptor.

1. Lee el **cuerpo crudo**, los bytes tal como llegaron. No lo vuelvas a serializar: cualquier cambio de espacios o de orden rompe la firma.
2. Separa `X-OpsWatch-Signature` por comas en `t` (segundos Unix) y `v1` (HMAC en hexadecimal).
3. Calcula `HMAC-SHA256` con el secreto **entero**, prefijo `whsec_` incluido y en UTF-8, como clave, sobre `t + "." + cuerpo`.
4. Compara tu resultado con `v1` en **tiempo constante** (`MessageDigest.isEqual`, `hmac.compare_digest`, `crypto.timingSafeEqual`).
5. Rechaza la petición si `t` se aleja más de **5 minutos** de tu reloj: así una petición capturada no se puede repetir más tarde con su firma.

Después de un `POST …/rotate-secret`, el secreto anterior deja de valer en el acto: actualiza el receptor antes o justo después de rotar.

### Ejemplo en Java

```java
static boolean verify(String secret, String header, byte[] body, Instant now) throws GeneralSecurityException {
    Map<String, String> parts = new HashMap<>();
    for (String part : header.split(",")) {
        String[] pair = part.split("=", 2);
        if (pair.length == 2) {
            parts.put(pair[0].strip(), pair[1].strip());
        }
    }
    String t = parts.get("t");
    String v1 = parts.get("v1");
    if (t == null || v1 == null || Math.abs(now.getEpochSecond() - Long.parseLong(t)) > 300) {
        return false;
    }
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
    byte[] expected = mac.doFinal(body);
    return MessageDigest.isEqual(expected, HexFormat.of().parseHex(v1));
}
```

### Ejemplo en Node.js

```js
const crypto = require("node:crypto");

function verify(secret, header, rawBody, nowSeconds = Math.floor(Date.now() / 1000)) {
  const parts = Object.fromEntries(header.split(",").map((part) => part.trim().split("=", 2)));
  if (!parts.t || !parts.v1 || Math.abs(nowSeconds - Number(parts.t)) > 300) return false;
  const expected = crypto.createHmac("sha256", secret).update(`${parts.t}.`).update(rawBody).digest();
  const received = Buffer.from(parts.v1, "hex");
  return received.length === expected.length && crypto.timingSafeEqual(expected, received);
}
```

### Comprobar a mano

```bash
printf '%s' "$t.$cuerpo" | openssl dgst -sha256 -hmac "$secreto"
```

## Qué espera OpsWatch de la respuesta

- **Un `2xx` en menos de 5 s.** Cualquier otra cosa es un intento fallido: un `4xx`, un `5xx`, una respuesta que tarda más o una conexión que no se establece. Si tienes trabajo largo, responde `2xx` primero y hazlo después.
- **Sin redirects.** Un `3xx` es un intento fallido y OpsWatch no lo sigue: el cuerpo firmado nunca sale hacia otra URL. Configura en el canal la URL final.
- **El cuerpo de tu respuesta no se lee.**
- Los fallos se reintentan a los 30 s, 2 min, 10 min, 30 min y 1 h. Tras el sexto intento, la entrega queda `FAILED`, y su motivo se ve en `GET /api/v1/notification-channels/{channelId}/deliveries`.
- La entrega es **at-least-once**: el mismo `id` puede llegar más de una vez, por ejemplo si OpsWatch se reinicia justo después de enviar. Descarta los repetidos por `id`.
- Los avisos pueden llegar **desordenados**, por ejemplo la resolución antes que un reintento de la apertura. Ordénalos por `occurredAt`.

## Qué exige OpsWatch de la URL

- `https`, con un certificado válido para el host.
- Un host que resuelva a una dirección pública. Se comprueba al guardar el canal y **otra vez en cada envío**: una URL que pase a resolver a una red privada falla sin que salga nada ([protección SSRF](../security/ssrf-protection.md#6-webhooks)).
- Puerto 443, o de 1024 a 65535.
