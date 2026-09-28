# Costos

Estado: diseño inicial · Última revisión: 2026-09-28

Es un proyecto personal de portafolio. El objetivo es demostrar criterio, no gasto: **ninguna pieza de infraestructura de pago entra solo para demostrar que se sabe usar.**

## Prioridades

1. Desarrollo local.
2. Docker y Docker Compose.
3. Servicios gratuitos u open source.
4. Despliegue barato.
5. Cloud gestionado solo donde aporte algo que no se consiga de otra forma.

## Costo por etapa

Precios orientativos a 2026-09. Se revisan antes de contratar nada.

| Etapa | Infraestructura | Costo mensual estimado |
|---|---|---|
| Diseño y Sprint 0 | Máquina local, GitHub (repo, Actions, GHCR) | 0 |
| V1 local (Fases 1–5) | Máquina local, Testcontainers, Mailpit | 0 |
| V1 desplegada (Fase 6) | Un VPS pequeño (2 vCPU, 2–4 GB) con staging y producción, dominio, TLS de Let's Encrypt y SMTP con plan gratuito | 0 a ~10 USD (0 con un nivel gratuito como Oracle Cloud Always Free; 4–8 USD con un VPS básico) más ~1 USD/mes prorrateado del dominio |
| V2 (Fases 7–9) | Prometheus, Grafana y el simulador de destinos en local o en el mismo VPS. Redis solo si se acepta ADR-009, como contenedor y no gestionado | Sin cambios, o +RAM en el VPS |
| Benchmarks | **En la máquina local** con límites de CPU y memoria en los contenedores. Las pruebas de 10 000 monitores no se hacen contra internet sino contra el simulador | 0 |
| V3–V5 (condicionadas) | Servicios adicionales y broker como contenedores en el mismo VPS o en un segundo VPS pequeño | +5–10 USD si hace falta un segundo host |

## Servicios gratuitos que se usan

| Necesidad | Servicio | Límite relevante |
|---|---|---|
| Repositorio, CI, registro de imágenes | GitHub, GitHub Actions, GHCR | Gratis para repositorios públicos |
| Escaneo de seguridad | Dependabot, secret scanning, CodeQL | Gratis para repositorios públicos |
| TLS | Let's Encrypt con Caddy | — |
| Email transaccional | Plan gratuito de un proveedor SMTP | Unos cientos de emails al día, de sobra para las notificaciones de un portafolio |
| Monitor externo del propio OpsWatch | Plan gratuito de un servicio de uptime | — |
| Observabilidad | Prometheus, Grafana, Tempo o Jaeger, autoalojados | — |

## Lo que se evita

| Evitado | Alternativa | Motivo |
|---|---|---|
| Kubernetes gestionado (EKS, GKE, AKS) | Docker Compose | El plano de control y los nodos cuestan dinero y horas sin resolver ningún problema de V1 |
| Kafka gestionado | Contenedor, si se llega a la Etapa 4 | Los planes gestionados cuestan decenas de USD al mes como mínimo |
| Base de datos gestionada | PostgreSQL en contenedor con copias de seguridad propias | Más operación, pero es una habilidad que el proyecto quiere mostrar |
| APM de pago | Stack de Grafana autoalojado | — |
| Balanceadores gestionados | Caddy | Un solo host |
| Checks multi-región en varias clouds | Una región | Funcionalidad aplazada |

## Controles de costo

- Retención de 30 días en `monitor_checks` ([retención](../database/data-retention.md)): el disco crece de forma acotada.
- Cuotas por organización: un usuario no puede multiplicar el costo del motor.
- Rotación de logs de Docker.
- Copias de seguridad con retención fija (7 diarias y 4 semanales).
- Alerta de disco al 80 % en el VPS (Fase 6).
- Cualquier servicio nuevo con costo recurrente requiere que su ADR incluya el costo mensual estimado.
