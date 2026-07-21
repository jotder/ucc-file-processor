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
* [Engine](engine.md) - `inspecto/` — the lean core: engine + control plane (`file-processor.jar`).
* [Connectors](connectors.md) - `inspecto-connectors/` — SFTP/FTP/FTPS/DB connectors (all network deps).
* [Agent](agent.md) - `inspecto-agent/` — optional AI assist skills (vendored kernel layer + eoiagent model transport).
* [Agent (hosted)](agent-hosted.md) - `inspecto-agent-hosted/` — hosted model providers (omitted from air-gapped builds).
* [Security](security.md) - `inspecto-security/` — Standard-only OIDC auth (`file-processor-security`),
  reactor-gated behind the `edition-standard` Maven profile — see also [auth & security](../editions/auth-security.md).
