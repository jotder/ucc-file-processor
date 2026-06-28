---
okf_version: "0.1"
---

# Inspecto Backend — Knowledge Bundle

Curated, agent- and human-friendly documentation for the **Inspecto Java backend** — the file-processing
engine, control plane, acquisition framework, flow-graph, and connectors. This bundle follows the
[Open Knowledge Format (OKF) v0.1](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md):
each `.md` file is one concept with YAML frontmatter; `index.md` files are progressive-disclosure listings.
The companion frontend bundle lives at `inspecto-ui/docs/okf/`.

Start with the [Overview](overview.md) and [Architecture](architecture.md), then drill into a module or layer.

## Start here

* [Overview](overview.md) - what the backend is, the tech stack, the module map.
* [Architecture](architecture.md) - the framework-free design (JDK HttpServer, manual DI, ServiceLoader SPI, virtual threads).
* [Modules](modules/) - the four Maven modules (engine, connectors, agent, agent-hosted).

## Layers

* [Engine](engine/) - ingestion, DuckDB, output/sinks, transform seams, parsing/grammar.
* [Acquisition](acquisition/) - the data-acquisition framework (phases A–F) and the remote connectors.
* [Control plane](control-plane/) - the HTTP Control API, events/metrics, jobs, multi-space.
* [Flow-graph](flow-graph/) - the authored-flow IR, validator, executor, and live execution.
* [Components](components/) - the reusable component registry (grammar/schema/transform/sink/alert).

## Cross-cutting

* [Config](config/) - TOON config + the safety validator.
* [Editions](editions/) - editions as build flavors, auth/security SPI, branch & release policy.
* [Agent](agent/) - the optional AI assist agent and hosted model providers.
* [Build & run](build-run/) - the verify loop, the DuckDB native-access flag, packaging, launch flags.
* [Gotchas](gotchas/) - the expensive-to-rediscover cross-cutting pitfalls.
