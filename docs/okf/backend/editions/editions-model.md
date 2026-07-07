---
type: Concept
title: Editions Model
description: Personal/Standard/Enterprise are build flavors — Maven profiles + ServiceLoader modules + -D flags, never branches.
resource: docs/EDITIONS.md
tags: [editions, build-flavors, maven, serviceloader]
timestamp: 2026-07-07T00:00:00Z
---

# Editions Model

**Editions are BUILD FLAVORS, never git branches.** One source tree (`master`) is the auth-free common core;
an edition is *which Maven modules + flags* are assembled from a given commit. A fix lands once in core and
every edition inherits it at build time — no cross-line cherry-picking. Authoritative doc: `docs/EDITIONS.md`.

Assembly mechanisms:

* **Maven profiles** — `-Pedition-personal` / `-Pedition-standard` control which modules + shade includes
  enter the fat-JAR.
* **An optional Maven module** — `inspecto-security` holds Standard-only code (OIDC via Nimbus, role
  mapping, token relay); it joins the reactor only under the `edition-standard` profile, so Personal never
  even compiles it. See [auth & security](auth-security.md).
* **`ServiceLoader`** — an absent module means the no-op impl is the only one discovered (same pattern as the
  optional [assist agent](../agent/assist-agent.md) and [connectors](../modules/connectors.md)).
* **`-D` flags** — e.g. `-Dauth.mode=none` (Personal) vs `-Dauth.mode=oidc` (Standard).
* **`package.ps1 -Edition …`** — emits per-edition bundles from one build (see [build & run](../build-run/build-test.md)).

One version spans all editions; artifacts differ by classifier. The matching branch policy is in
[branching & release](branching-release.md).
