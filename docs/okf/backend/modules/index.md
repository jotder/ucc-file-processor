# Modules

The five backend Maven modules. Directory names were renamed 2026-06-12; the artifactIds were not (dir ≠
artifactId). The core stays lean — network, hosted-AI, and auth dependencies are isolated in their own
modules.

# Modules

* [Engine](engine.md) - `inspecto/` — the lean core: engine + control plane (`file-processor.jar`).
* [Connectors](connectors.md) - `inspecto-connectors/` — SFTP/FTP/FTPS/DB connectors (all network deps).
* [Agent](agent.md) - `inspecto-agent/` — optional AI assist skills (vendored kernel layer + eoiagent model transport).
* [Agent (hosted)](agent-hosted.md) - `inspecto-agent-hosted/` — hosted model providers (omitted from air-gapped builds).
* [Security](security.md) - `inspecto-security/` — Standard-only OIDC auth (`file-processor-security`),
  reactor-gated behind the `edition-standard` Maven profile — see also [auth & security](../editions/auth-security.md).
