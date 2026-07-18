---
type: Feature
title: Connections
description: Schema-driven connection workbench — 5 connector types, SSH tunnel + proxy, test/probe/sample.
resource: inspecto-ui/src/app/modules/admin/connections/connections.routes.ts
tags: [feature, connections, acquisition, ssh-tunnel, proxy]
timestamp: 2026-06-28T00:00:00Z
---

# Connections

Route `/connections` (Acquisition nav group). A connect/explore/test/sample workbench. The connection-form
dialog is **schema-driven** across 5 connector types (Database · FTP · FTPS · Local · SFTP), with an
**SSH tunnel (bastion)** option on top of the form (default-on) and a **proxy** option, each with a Test
button; a routing popover opens on a routing icon (both toggles default-off). Connections render as **cards**
(not a [data-table](../design-system/data-table.md)). Backed by `ConnectionsService` / `ConnectionProbeService`;
offline via the `mockConnectionProbe` [interceptor](../conventions/mock-backends.md). Since 2026-07-18 the
real backend serves the whole surface too: `proxy` is a real `ConnectionProfile` sub-block
(`target=proxy` test works), and probe/explore/sample are live for local/SFTP/FTP/FTPS — other connectors
probe as `skipped` and 501 on explore/sample (see `okf/backend/acquisition/connectors.md`).
