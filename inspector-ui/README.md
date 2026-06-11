# Inspector — UCC File Processor operator console

A web console for operating the UCC File Processor, built on the **DevExtreme Angular** stack
(Angular 21 + DevExtreme 25.2, scaffolded from the DevExtreme Angular Template). It drives the
backend's REST control plane (`com.gamma.control.ControlApi`) — monitoring pipelines/batches,
scheduling jobs, watching enrichment, browsing the data catalog + lineage graph, authoring/validating
configs, reviewing failure diagnoses, and running the AI assist skills.

> DevExtreme is a commercial library; the template runs on the trial. A license is required for
> production use.

> **Operators:** this README covers building and serving the SPA. For using the console day-to-day
> (connecting with tokens, what each screen does, common tasks), see the
> **[Operator Console user guide](../docs/operator-console.md)**.

## Prerequisites

- Node `^24.15.0`
- pnpm `11.5.1` (`npm i -g pnpm@11.5.1`)

## Install

```bash
cd inspector-ui
pnpm install        # postinstall builds the bundled DevExtreme themes
```

## Develop (live, against a running backend)

Start the backend with CORS enabled for the dev server and a dev token:

```bash
java -Dcontrol.token=dev -Dassist.read.token=dev -Dcontrol.cors=http://localhost:4200 \
     -cp file-processor.jar com.gamma.control.ControlApi <config-dir>
```

Then run the SPA:

```bash
pnpm start          # ng serve on http://localhost:4200
```

`proxy.conf.json` forwards `/api/*` → `http://localhost:8080` (stripping `/api`), so browser calls
are same-origin. On the **Connect** screen, paste the control and/or assist token.

## Build (production bundle)

```bash
pnpm run build      # → inspector-ui/dist/DevExtreme-app
```

The production build calls the API at a relative base (same origin), so it is served by the backend
itself — no CORS needed. `package.ps1` bundles `inspector-ui/dist` next to the jar as `ui/`, and the generated
`serve.*` scripts launch `ControlApi` with `-Dui.dir=./ui`; deep links fall back to `index.html`, and
static assets are public so the shell loads before a token is supplied.

## Auth model

There is no login endpoint. The backend uses scoped bearer tokens (`CONTROL`, `ASSIST_READ`,
`ASSIST_WRITE`) supplied server-side via `-D` system properties. The operator pastes their token(s)
on the Connect screen; an HTTP interceptor attaches `Authorization: Bearer …`. CONTROL is a superuser
(also satisfies assist); CONTROL-only actions (trigger / pause / reprocess / run-now) are disabled
when only an assist token is held.

## Layout

```
src/app/
  shared/api/         typed API services + DTOs + interceptors + token store + auto-refresh helper
  shared/components/  connect-form, assist-panel (reusable), header/footer/nav (template)
  pages/              dashboard, pipelines (+ pipeline-detail), jobs, enrichment,
                      catalog, config, diagnoses, assist
```

## Scope & follow-ups

- **Config authoring** closes the loop: draft → validate → **Save to server**
  (`POST /config/write`, `assist.write` scope, 409→overwrite prompt, 422→findings shown) →
  **Register pipeline** (`POST /pipelines`, `CONTROL`). Both need `-Dassist.write.root` on the
  server; without it, saving errors cleanly and the copy-the-preview path still works.
- The **catalog graph** renders as an interactive read-only `dxDiagram` with a per-kind legend;
  clicking a node opens its detail popup.
- **Lint** (`pnpm lint`) — angular-eslint + typescript-eslint. First-party code follows the strict
  recommended rules; files vendored from the DevExtreme template are scoped to a looser bar. All
  templates use the built-in `@if`/`@for` control flow (migrated; `prefer-control-flow` is enforced).
- **Unit tests** (`pnpm test`, or `pnpm test:ci` for a single run) — Vitest via the
  `@angular/build:unit-test` builder (jsdom). The suite covers the API/auth layer (`api-base`,
  `token-store`, `auth.service`, `auth.interceptor`, `pipelines.service`, `auto-refresh`) plus
  component/graph specs (`catalog.component`, `catalog-graph`, `config.component`).
- **e2e** — a thin backend smoke (`src/e2e/backend-smoke.spec.ts`), skipped unless `E2E_BASE_URL`
  is set: `E2E_BASE_URL=http://localhost:8080 E2E_TOKEN=dev pnpm test:ci`.
- CI (`.github/workflows/ui.yml`) gates on **lint → unit tests → production build → development build**.

## Testing & linting

```bash
pnpm lint            # angular-eslint
pnpm test            # Vitest (watch in a TTY)
pnpm test:ci         # Vitest, single run (used by CI)
```
