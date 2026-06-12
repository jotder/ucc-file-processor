# Inspector — operator console for the UCC file-processing platform

Angular SPA on the gamma-analytics admin template (Angular 21 + Angular Material + Tailwind),
with **ag-Grid Community** for grids, **Chart.js** for the dashboard charts and **AntV G6** for
the catalog metadata graph. Replaced the original DevExtreme app in v4.1 — the phase-by-phase
port is documented in [`docs/devextreme-migration-plan.md`](docs/devextreme-migration-plan.md).

> The template is a licensed ThemeForest product (see `package.json` / `CREDITS`) — don't
> redistribute `src/@gamma/` outside this project.

## Layout

```
src/@gamma/            template core library (layouts, components, theming) — avoid editing
src/app/ucc/           UCC layer: API services + interceptors, auth, grid kit, assist panel, chart host
src/app/modules/admin/ one folder per screen: dashboard, pipelines, pipeline-detail, jobs,
                       enrichment, catalog, diagnoses, config, assist, connect
src/e2e/               guarded backend-smoke spec (runs only when E2E_BASE_URL is set)
```

## Develop

```bash
npm install
npm start          # ng serve on :4204; /api/* proxied to ControlApi on :8080 (proxy.conf.json)
```

Start the backend with CORS for the dev origin (`-Dcontrol.cors=http://localhost:4204`), open
`http://localhost:4204/` and paste your operator token(s) on the Connect screen. There is no
username/password login — see the
[Operator Console guide](../docs/operator-console.md).

## Test & build

```bash
npm run test:ci    # Vitest (jsdom) via @angular/build:unit-test
npm run build      # production build → dist/
# optional e2e smoke against a running backend:
E2E_BASE_URL=http://localhost:8080 E2E_TOKEN=dev npm run test:ci
```

In production the SPA is served same-origin by `ControlApi` (`-Dui.dir`); the deploy bundle is
assembled by `inspecto/package.ps1`. CI: `.github/workflows/ui.yml`.
