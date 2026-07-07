# Conventions

The binding rules for any inspecto-ui change. The build breaks or review fails if §0 non-negotiables are
violated. The [testing & build](testing-and-build.md) concept is the Definition of Done.

# Rules

* [Design tokens & styling](design-system-tokens.md) - no hardcoded colors (the `lint:tokens` guard), gamma `--gamma-*` vars, the sanctioned color owners.
* [Accessibility](accessibility.md) - WCAG 2.2 AA, one `<h1>`, icon-button labels, focus-visible, the axe-core gate.
* [Forms & state](forms-and-state.md) - reactive forms with inline errors; signals; optimistic mutation.
* [API & data](api-and-data.md) - service per resource, `apiUrl`/`toParams`, blob downloads, secrets, the auth-free core.
* [Errors & connectivity](errors-and-connectivity.md) - the global error interceptor and the connectivity banner.
* [Routing & navigation](routing-and-navigation.md) - lazy routes, the nav data file, breadcrumbs, global search.
* [Multi-space](multi-space.md) - `SpacesService` + the `spaceInterceptor` path rewrite.
* [Mock backends](mock-backends.md) - env-gated HTTP interceptors for fully-offline operation.
* [Testing & build](testing-and-build.md) - vitest + axe, the token guard, the production build, the definition of done.
