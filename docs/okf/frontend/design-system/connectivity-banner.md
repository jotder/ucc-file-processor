---
type: Component
title: Connectivity Banner
description: The app-wide offline / backend-down strip, mounted once in the layout shell.
resource: inspecto-ui/src/app/inspecto/components/connectivity-banner.component.ts
tags: [design-system, connectivity, offline, color-owner]
timestamp: 2026-06-28T00:00:00Z
---

# Connectivity Banner

`<inspecto-connectivity-banner>` is the app-wide strip shown when the backend is unreachable
(`role="alert"`, a Retry button that re-probes `/health`). It is **already mounted** as the first child of
`layout.component` (`display:contents` — consumes no space when hidden, stacks full-width when shown), driven
by `ConnectivityService` signals. One of the four sanctioned [color owners](../conventions/design-system-tokens.md).

Do **not** add a per-screen "unreachable" toast — the global [error interceptor](../conventions/errors-and-connectivity.md)
reports `status 0` to `ConnectivityService`, which drives this banner. For per-screen notices use the
[alert](alert.md) instead.
