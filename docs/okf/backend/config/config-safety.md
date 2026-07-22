---
type: Concept
title: Config Safety Validator
description: The hard-fail gate that path-jails writes, bounds numeric config, and allow-lists output formats.
resource: inspecto-config/src/main/java/com/gamma/config/safety/ConfigSafetyValidator.java
tags: [config, safety, validation, path-jail, security]
timestamp: 2026-06-28T00:00:00Z
---

# Config Safety Validator

`ConfigSafetyValidator` (`inspecto-config/src/main/java/com/gamma/config/safety/ConfigSafetyValidator.java`) is a
purely-static, zero-dependency hard-fail gate (since v3.5.0). `check(configType, rawMap, policy)` returns
`ERROR`-severity `Finding`s for any violation. It enforces three things:

* **Path jail** — every `dirs.*` field + `output.ducklake.data_path` must resolve under the policy's
  `allowedRoots`; rejects `..` escapes, UNC paths, and symlink escapes (real-path re-checked).
* **Numeric bounds** — `processing.threads`, `processing.duckdb_threads`, `processing.batch.max_files`, and
  the `skip_*` values against policy limits; `retention_days >= 1` when duplicate-check is on.
* **Output allow-list** — `output.format`/`output.compression` restricted to known values; DuckLake requires
  its connection fields when enabled.

Only `pipeline` and `enrichment` config types have a write surface to gate. This is tied to the write-gate:
when `-Dassist.write.root` is set, writes are jailed to that root and validated here (see
[auth & security](../editions/auth-security.md)).
