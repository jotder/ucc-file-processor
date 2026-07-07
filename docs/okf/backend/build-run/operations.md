---
type: Concept
title: Operations & Launch Flags
description: The key -D launch flags, run modes, and the production-investigation guide.
resource: inspecto/package.ps1
tags: [operations, launch, flags, run, troubleshooting]
timestamp: 2026-06-28T00:00:00Z
---

# Operations & Launch Flags

The server entry is [`ControlApi.main`](../control-plane/control-api.md) (the `com.gamma.util.MainApp` CLI is a
separate pre-ETL tool suite — search/copy/extract/backup/prepare-inbox/create-schema/reprocess).

## Key `-D` launch flags

| Flag | Purpose |
|---|---|
| `-Dcontrol.port=<n>` | HTTP port for the control API (default `8080`). |
| `-Dspaces.root=<dir>` | [Multi-space](../control-plane/multi-space.md) root (default `./spaces`); omit for single-tenant. |
| `-Dui.dir=./ui` | Serve the bundled Angular SPA; omit to disable UI serving. |
| `-Dcontrol.cors=<origin>` | CORS origin allow-list for the control plane. |
| `-Dassist.write.root=<dir>` | Enable config/flow/connection write-back; absent → mutations return `503` (see [auth & security](../editions/auth-security.md)). |
| `-Dacquire.ledger.backend=db` | Use the durable [DB acquisition ledger](../acquisition/framework.md) instead of in-memory. |
| `-Dauth.mode=none\|oidc` | Edition runtime toggle (Personal `none`; Standard `oidc`). |

Plus `--enable-native-access=ALL-UNNAMED` is always required (see [build & test](build-test.md)).

## Investigating production

The living production-investigation guide (process/events/metrics/state/Control API/troubleshooting) is
`docs/ADVANCED_GUIDE.md`. Observability primitives: [events & metrics](../control-plane/events-metrics.md)
(`/metrics` Prometheus text, `/events/search`). Performance tuning: `docs/performance.md`.
