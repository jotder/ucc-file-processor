# Modules

The four backend Maven modules. Directory names were renamed 2026-06-12; the artifactIds were not (dir ≠
artifactId). The core stays lean — network and hosted-AI dependencies are isolated in their own modules.

# Modules

* [Engine](engine.md) - `inspecto/` — the lean core: engine + control plane (`file-processor.jar`).
* [Connectors](connectors.md) - `inspecto-connectors/` — SFTP/FTP/FTPS/DB connectors (all network deps).
* [Agent](agent.md) - `inspecto-agent/` — optional AI assist skills on `agent-kernel`.
* [Agent (hosted)](agent-hosted.md) - `inspecto-agent-hosted/` — hosted model providers (omitted from air-gapped builds).
