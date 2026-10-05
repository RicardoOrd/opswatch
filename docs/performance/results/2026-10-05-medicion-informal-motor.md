# Resultado: medición informal del motor (100 y 1 000 monitores a 30 s): 2026-10-05

**No es un benchmark formal** (OW-030): una sola ejecución de 3 min por escenario, sin límites de CPU ni de memoria y con la base de desarrollo. Sirve para comprobar que las métricas de la Fase 3 dicen algo útil y que, a esta escala, el motor va sobrado. Los benchmarks B1 a B5 de la Fase 7 siguen el [procedimiento](../benchmark-plan.md#procedimiento) completo.

- Commit: `9642784` (`main`) más la rama de OW-030 · JDK 25.0.4 (Temurin) · Spring Boot 4.1.1 · PostgreSQL 18.6 (Alpine, Docker Desktop)
- Hardware: AMD Ryzen 5 5600X (6 núcleos, 12 hilos), 16 GB, Windows 11 · Sin límites para la aplicación ni para PostgreSQL
- Aplicación: `java -jar target/opswatch.jar --spring.profiles.active=local --opswatch.egress.allowed-private-cidrs=127.0.0.0/8`, con los valores por defecto del motor (semáforo de 200, dispatch cada 1 s, lote de 500)
- Destino: un servidor HTTP de Node en `127.0.0.1:9100` que responde `200` tras 20 a 60 ms
- Monitores: insertados por SQL con `interval_seconds = 30`, `timeout_ms = 5000` y el primer check repartido en 30 s; GET a `http://127.0.0.1:9100/health`
- Medición: diferencia entre dos scrapes de `/actuator/prometheus` separados 3 min, tras 40 s de calentamiento. Percentiles interpolados dentro de los buckets, así que su precisión es la de los buckets

| Métrica | 100 monitores | 1 000 monitores |
|---|---|---|
| Checks/s esperados | 3,33 | 33,3 |
| Checks/s medidos | 3,35 (619 en 185 s) | 33,1 (5 995 en 181 s) |
| `outcome` distinto de `UP` | 0 | 0 |
| Lag p50 / p95 / p99 | 0,51 / 0,96 / 1,31 s | 0,51 / 0,97 / 1,42 s |
| Duración del check p50 / p95 (bucket) | 35 ms / entre 50 y 100 ms (máximo 62 ms) | 35 ms / entre 50 y 100 ms (máximo 64 ms) |
| Claim p50 / p95 | 7 / 10 ms | 8 / 17 ms |
| Dispatches saturados | 0 | 0 |
| Vencidos (`overdue`) al final | 0 | 0 |
| CPU de la aplicación | 2,97 s en 185 s (1,6 % de un núcleo) | 11,4 s en 181 s (6,3 % de un núcleo) |
| Heap usado al final | 95 MB | 92 MB |
| Hilos vivos | 37 | 38 |
| Espaciado entre checks de un monitor (SQL) | 29,23 a 30,32 s, mediana 30,25 s | 29,23 a 30,32 s, mediana 30,27 s |
| Checks a menos de 29 s del anterior | 0 | 0 |

## Observaciones

- **El lag lo pone el intervalo del dispatch**, no la carga: es casi uniforme entre 0 y 1 s (p50 ≈ 0,5 s) en los dos escenarios. El p95 de 0,97 s queda por debajo del objetivo V1 (< 2 s) con margen.
- **Sin deriva**: el espaciado oscila alrededor de 30 s (30,27 s la mayoría de las veces y 29,26 s cuando el dispatch da la vuelta) porque el dispatch se repite cada ~1,01 s y 30 s no es múltiplo; cada check se programa desde el anterior programado, no desde que se hizo.
- **Sin duplicados ni omisiones**: ningún check llegó antes de 29 s del anterior, y todos los monitores tuvieron checks.
- El claim cuesta lo mismo con 1 000 monitores que con 100 (p95 por debajo de 20 ms, el umbral D2 es de 50 ms).
- La base de desarrollo tenía 46 monitores de la verificación de la v0.2.0 apuntando a `api.example.com`: el motor los comprobó con el DNS real al arrancar (los 46 dieron `DNS_FAILURE`, sin tráfico a ningún servidor) y se pausaron antes de medir.

## Cuello de botella identificado

Ninguno a esta escala. Ninguna de las condiciones M1 a M5 ni D1 a D3 del [plan](../benchmark-plan.md#criterios-de-cuello-de-botella) se acerca a su umbral.

## Decisión o siguiente paso

Nada que cambiar en la v0.3.0. Los escenarios de 5 000 y 10 000 monitores, con límites de 2 vCPU y 2 GB y destinos lentos o que fallan, son de la Fase 7 (B3 a B5).
