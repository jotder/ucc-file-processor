# Modules

The backend Maven modules. Directory names were renamed 2026-06-12; the artifactIds were not (dir ≠
artifactId). The core stays lean — network, hosted-AI, and auth dependencies are isolated in their own
modules. Reactor shape, version management, and the module-extraction playbook:
[reactor.md](reactor.md).

# Modules

* [Reactor & modularization](reactor.md) - build order, parent `dependencyManagement`, extraction rules (S5 + WS-D, 2026-07-21).
* `inspecto-api/` — dependency-free leaf: the `@PublicApi` annotation (`file-processor-api`).
* `inspecto-util/` — leaf (w.r.t. `com.gamma`): DuckDB access + CSV/file/tar helpers (`file-processor-util`).
* `inspecto-config/` — config spec/codec/safety (`file-processor-config`); depends only on fp-api.
* `inspecto-sql/` — sandboxed DuckDB SQL: `SqlSandbox`/`SqlOracle`/`SqlGuard`/`SqlViews` (`file-processor-sql`); depends on fp-api/config/util.
* `inspecto-etl/` — `com.gamma.etl`: ingest/transform/output core — `PipelineConfig`, ingesters, batch planning, quarantine, partitioned Parquet (`file-processor-etl`).
* `inspecto-event/` — `com.gamma.event` + `metrics`: the Operational-Intelligence event store + metric registry; owns `logback.xml` (`file-processor-event`).
* `inspecto-acquire/` — `com.gamma.acquire`: connectors, connection profiles/registry/workbench, fingerprint ledger, stability gate, retry/circuit-breaker (`file-processor-acquire`).
* `inspecto-engine/` — the engine cluster: `signal`/`query`/`pipeline`/`inspector`/`ingester`/`ops`/`job`/`enrich`/`alert`/`notify`/`catalog`; holds the fat-jar entry points (`file-processor-engine`).
* [Core](engine.md) - `inspecto/` — the composition root: control plane + application packages, ships `file-processor.jar`. The engine was extracted to sibling modules `inspecto-engine`/`-etl`/`-event`/`-acquire` in WS-D (see [reactor.md](reactor.md)).
* [Connectors](connectors.md) - `inspecto-connectors/` — SFTP/FTP/FTPS/DB connectors (all network deps).
* [Agent](agent.md) - `inspecto-agent/` — optional AI assist skills (vendored kernel layer + eoiagent model transport).
* [Agent (hosted)](agent-hosted.md) - `inspecto-agent-hosted/` — hosted model providers (omitted from air-gapped builds).
* [Security](security.md) - `inspecto-security/` — Standard-only OIDC auth (`file-processor-security`),
  reactor-gated behind the `edition-standard` Maven profile — see also [auth & security](../editions/auth-security.md).
