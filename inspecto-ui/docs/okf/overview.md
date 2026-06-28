---
type: Overview
title: Inspecto UI Overview
description: The Angular operator console for Inspecto — its purpose, tech stack, and how it boots.
resource: inspecto-ui/
tags: [inspecto-ui, angular, overview, tech-stack]
timestamp: 2026-06-28T00:00:00Z
---

# Overview

**inspecto-ui** is the operator console for Inspecto (a Java/DuckDB file-processing engine + control
plane). It is a single-page Angular application built on a vendored gamma/Fuse shell. Operators use it to
author pipelines and flows, configure connections and parsers, browse the data catalog, and monitor the
operational event stream (events, alerts, cases/issues, enrichment, jobs).

## Tech stack

* **Framework**: Angular 21, **standalone components** (no NgModules), new control flow (`@if`/`@for`/`@switch`), `@defer` for lazy chunks.
* **Language**: TypeScript (strict); explicit return types; `inject()` over constructor params.
* **State**: Angular **signals** (local + service-held) + **RxJS** for async/streams. No NgRx / global store.
* **UI**: Angular **Material (M2)** + **Tailwind** on the gamma/Fuse shell; **ag-Grid 35** tables; **Chart.js** charts; **AntV G6** graphs; **CodeMirror 6** for the SQL editor.
* **Forms**: Reactive forms (`FormBuilder`/`Validators`) + inline `<mat-error>`.
* **Testing**: **vitest** (jsdom) + `TestBed`; **axe-core** a11y assertions.
* **Package manager**: npm (`npm ci` in CI; keep `package-lock.json` in sync).

## How it boots

The app is **auth-free** in the core / Personal edition (auth was removed 2026-06-16): no token store,
interceptor, route guard, or `/connect` screen — requests carry no bearer and the app boots straight to
`/dashboard`. Standard/Enterprise editions re-add auth out-of-band via a security module + `Authenticator`
SPI (not in this tree). See [API & data conventions](./conventions/api-and-data.md).

## Where things live

* `src/app/inspecto/` — shared core (API services, design-system components, grid/query/rule/data-table, theme, testing). See the [Design system](./design-system) and [API services](./services/api-services.md).
* `src/app/modules/admin/<feature>/` — the lazy-loaded [feature screens](./features).
* `src/app/layout/` — the app shell (connectivity banner, header, nav).
* `src/@gamma/`, `src/app/modules/auth/` — **vendored** gamma/Fuse scaffolding (out of scope: don't restyle/audit/guard).

See [Architecture](./architecture.md) for the layering rules.
