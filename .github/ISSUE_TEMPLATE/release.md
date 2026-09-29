---
name: Release
about: Publicar la versión que cierra un milestone (docs/development/versioning.md#proceso-de-release)
title: "Release vX.Y.Z"
labels: [devops]
---

Milestone: <!-- el milestone que cierra esta release -->

- [ ] Todas las issues del milestone cerradas (o movidas con un motivo escrito)
- [ ] Criterios de aceptación de la fase comprobados sobre `main` (docs/roadmap/roadmap.md)
- [ ] CI en verde en el último commit de `main`
- [ ] README: lo publicado pasa de *Planned* a *Implemented*
- [ ] Documentación de la fase coherente con el código (catálogos de endpoints y de propiedades, modelo de dominio)
- [ ] Tag `vX.Y.Z` sobre `main` y release de GitHub con notas revisadas
- [ ] Desde la Fase 6: despliegue en staging, smoke tests y promoción a producción
