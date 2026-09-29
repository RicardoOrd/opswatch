Closes #

## Qué

<!-- Qué cambia, en pocas líneas -->

## Por qué

<!-- Problema que resuelve o issue que implementa -->

## Cómo se probó

<!-- Tests añadidos y su tipo (unitario, integración, módulo, seguridad, arquitectura) y comprobaciones manuales -->

## Seguridad

<!-- Impacto en autorización, validación, datos sensibles, SSRF... o por qué no hay impacto -->

## Checklist ([Definition of Done](https://github.com/RicardoOrd/opswatch/blob/main/docs/roadmap/definition-of-done.md))

- [ ] Cumple los criterios de aceptación de la issue
- [ ] `./mvnw verify` en verde (tests, formato, límites de módulo)
- [ ] Nada sensible en código, logs, respuestas ni errores
- [ ] Endpoints nuevos: fila en la matriz de autorización y test de IDOR
- [ ] Recursos cargados por id pasan por `AccessControl`
- [ ] Ningún I/O externo dentro de transacciones; HTTP saliente solo por `egress`
- [ ] Migraciones nuevas, compatibles con la versión anterior
- [ ] Documentación de `docs/` y backlog actualizados en este PR
