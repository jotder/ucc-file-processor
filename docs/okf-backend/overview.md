---
type: Overview
title: Inspecto Backend Overview
description: The Java file-processing engine + control plane — tech stack, module map, and design ethos.
resource: inspecto/
tags: [inspecto, backend, overview, java, duckdb, toon]
timestamp: 2026-06-28T00:00:00Z
---

# Overview

**Inspecto** (formerly *UCC File Processor*) is a high-throughput file-processing engine with an embedded
control plane. It ingests files (local or remote), parses them, applies a schema + transforms, and writes
partitioned columnar output — all backed by an embedded **DuckDB**. An operator console
(inspecto-ui — its own OKF bundle at `inspecto-ui/docs/okf/`) drives it over an HTTP control API.

## Tech stack

* **Java 26**, **Maven** multi-module reactor.
* Embedded **DuckDB** (native, via the Appender API) for ingest/transform/output.
* **TOON** configuration (`.toon` files via JToon) — see [TOON config](./config/toon-config.md).
* Framework-free: the JDK's built-in `HttpServer`, manual dependency injection, `ServiceLoader` SPIs, and
  **virtual threads** — no Spring/web framework. See [Architecture](./architecture.md).

## Module map

The directory names were renamed 2026-06-12; the Maven **artifactIds were not** (so dir ≠ artifactId).

| Dir | Role | artifactId / jar |
|---|---|---|
| `inspecto/` | engine + control plane (lean core) | `file-processor` / `file-processor.jar` |
| `inspecto-connectors/` | remote connectors (SFTP/FTP/FTPS/DB) — all network deps | `file-processor-connectors` |
| `inspecto-agent/` | optional AI assist skills (vendored kernel layer + eoiagent transport) | `file-processor-agent` |
| `inspecto-agent-hosted/` | hosted model providers (omitted from air-gapped builds) | `file-processor-agent-hosted` |
| `inspecto-ui/` | Angular SPA (served by the engine) | — (npm) |

See [Modules](./modules) for each one.

## Design ethos

* **Keep the core lean** — all network deps live in [connectors](./modules/connectors.md); hosted-AI SDKs in
  [agent-hosted](./modules/agent-hosted.md) (physically absent from air-gapped builds).
* **Editions are build flavors, never git branches** — one auth-free common core, assembled per edition via
  Maven profiles + `ServiceLoader` + `-D` flags. See [Editions](./editions/editions-model.md).
* **Mainline** `master`; current release line `4.x`. See [branch & release policy](./editions/branching-release.md).
