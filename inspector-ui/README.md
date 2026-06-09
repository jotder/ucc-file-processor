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

- **Config authoring** is draft → validate → copy: there is no write-to-disk endpoint, so the
  operator commits the generated `.toon` manually.
- The **catalog graph** is rendered as nodes/edges grids; an interactive `dxDiagram` is a future
  enhancement.
- **Lint** (`pnpm lint`) — angular-eslint + typescript-eslint. First-party code follows the strict
  recommended rules; files vendored from the DevExtreme template are scoped to a looser bar, and the
  `*ngIf`/`*ngFor` → built-in control-flow migration is deferred (documented in `eslint.config.js`).
- **Unit tests** (`pnpm test`, or `pnpm test:ci` for a single run) — Vitest via the
  `@angular/build:unit-test` builder (jsdom). The starter suite covers the API/auth layer
  (`api-base`, `token-store`, `auth.service`, `auth.interceptor`, `pipelines.service`, `auto-refresh`);
  component-level specs are a follow-up.
- **e2e** is not yet wired (a thin smoke against a running backend is a follow-up).
- CI (`.github/workflows/ui.yml`) gates on **lint → unit tests → production build → development build**.

## Testing & linting

```bash
pnpm lint            # angular-eslint
pnpm test            # Vitest (watch in a TTY)
pnpm test:ci         # Vitest, single run (used by CI)
```
