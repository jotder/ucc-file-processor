---
okf_version: "0.1"
---

# Inspecto Backend — Knowledge Bundle

Curated, agent- and human-friendly documentation for the **Inspecto Java backend** — the file-processing
engine, control plane, acquisition framework, pipeline-graph, and connectors. This bundle follows the
[Open Knowledge Format (OKF) v0.1](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md):
each `.md` file is one concept with YAML frontmatter; `index.md` files are progressive-disclosure listings.
Part of the [consolidated bundle](../index.md); the companion frontend section lives at [`../frontend/`](../frontend/index.md).

Start with the [Overview](overview.md) and [Architecture](architecture.md), then drill into a module or layer.

## Start here

* [Overview](overview.md) - what the backend is, the tech stack, the module map.
* [Architecture](architecture.md) - the framework-free design (JDK HttpServer, manual DI, ServiceLoader SPI, virtual threads).
* [Modules](modules/) - the five Maven modules (engine, connectors, agent, agent-hosted, security).

## Layers

* [Engine](engine/) - ingestion, DuckDB, output/sinks, transform seams, parsing/grammar.
* [Acquisition](acquisition/) - the data-acquisition framework (phases A–F) and the remote connectors.
* [Control plane](control-plane/) - the HTTP Control API, the versioned `/api/v1` contract, queries,
  events/metrics, jobs, multi-space.
* [Pipeline-graph](pipeline-graph/) - the authored-Pipeline IR, validator, executor, and live execution.
* [Components](components/) - the reusable component registry (grammar/schema/transform/sink/alert +
  dataset/widget/dashboard/query).

## Cross-cutting

* [Config](config/) - TOON config + the safety validator.
* [Editions](editions/) - editions as build flavors, auth/security SPI + the `inspecto-security` module,
  branch & release policy.
* [Agent](agent/) - the optional AI assist agent (vendored kernel + [eoiagent](../agentic/index.md)
  transport) and hosted model providers.
* [Build & run](build-run/) - the verify loop, the DuckDB native-access flag, packaging, launch flags.
* [Gotchas](gotchas/) - the expensive-to-rediscover cross-cutting pitfalls.
