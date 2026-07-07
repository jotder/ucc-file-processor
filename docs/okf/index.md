---
okf_version: "0.1"
---

# Inspecto — Consolidated Knowledge Bundle

The **one** [Open Knowledge Format (OKF) v0.1](https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md)
bundle for the whole Inspecto platform: each `.md` file is one concept with YAML frontmatter; `index.md`
files are progressive-disclosure listings. Concept files **summarize and link** the deep topic docs
(each cites its authoritative doc); they don't replace them. Vocabulary is binding per
[`GLOSSARY.md`](../GLOSSARY.md).

Consolidated 2026-07-07 from the two former bundles (`docs/okf-backend/`, `inspecto-ui/docs/okf/`) plus
a new distilled section for the agentic framework.

## Sections

* [Frontend](frontend/) — **inspecto-ui**, the Angular operator console: architecture, conventions,
  the shared design system, the ~35 feature screens (incl. the Studio, Geo Map Analysis, and Link
  Analysis studios), API services.
* [Backend](backend/) — the Java engine + control plane: modules, engine layers, acquisition,
  the versioned `/api/v1` contract, pipeline-graph, components, config, editions & security, agent,
  build/run, gotchas.
* [Agentic](agentic/) — **eoiagent**, the embeddable agent framework (separate repo,
  `C:/sandbox/agent-brainstorm`) that supplies Inspecto's model transport — distilled map + the
  Inspecto integration seam. The framework's authoritative docs live in its own repo.

## Companions

* Current platform requirements + MoSCoW: [`REQUIREMENTS.md`](../REQUIREMENTS.md)
* Stakeholder-facing set: [`../stakeholders/`](../stakeholders/README.md)
* Curated map of all docs: [`INDEX.md`](../INDEX.md)

Each section keeps its own `log.md` changelog.
