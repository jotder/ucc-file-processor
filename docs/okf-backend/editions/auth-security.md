---
type: Concept
title: Auth & Security
description: Auth removed from the core (2026-06-16); the future Authenticator SPI; the separate -Dassist.write.root write-gate.
resource: docs/EDITIONS.md
tags: [auth, security, spi, oidc, write-gate]
timestamp: 2026-06-28T00:00:00Z
---

# Auth & Security

**All auth was removed from `master`/the common core on 2026-06-16.** Personal is genuinely auth-free: every
[`ControlApi`](../control-plane/control-api.md) route is open, the SPA boots straight to `/dashboard`, and there
is no token paste / guard / interceptor. The removed hand-rolled bearer-token plane (per-route `Scope`,
`-Dcontrol.token`, the Angular token screen) is gone.

**Standard/Enterprise re-add auth out-of-band** via an `Authenticator` SPI in the (not-yet-built)
`inspecto-security` Maven module: an OIDC resource server (Nimbus + JWKS for JWT validation) + RBAC/ABAC from
IAM claims; Angular uses OIDC Auth-Code + PKCE. Because it's a [build flavor](editions-model.md), the core
carries zero auth code — the module is simply present or absent.

**The write-gate is separate from auth and stays in all editions.** `-Dassist.write.root` is a path-jailed
filesystem gate on mutation routes (config writes, connection writes, flow CRUD, pipeline creation): absent →
those routes return **`503`**; present → writes are jailed to that root and validated by
[`ConfigSafetyValidator`](../config/config-safety.md). It is an ops decision about whether this instance may
write — not authentication.
