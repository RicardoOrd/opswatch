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
- [ ] `pom.xml` en `X.Y.Z` en el PR de docs de cierre, que es el commit del tag
- [ ] Tag `vX.Y.Z` sobre `main` y release de GitHub con notas revisadas
- [ ] `pom.xml` en `X.(Y+1).0-SNAPSHOT` en `main` justo después del tag
- [ ] Desde la Fase 6: despliegue en staging, smoke tests y promoción a producción
