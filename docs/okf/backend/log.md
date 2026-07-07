# Log

## 2026-07-07
* **Consolidated**: bundle moved `docs/okf-backend/` → `docs/okf/backend/` under the new
  [consolidated bundle](../index.md) (frontend at `../frontend/`, new `../agentic/` section);
  `flow-graph/` renamed [`pipeline-graph/`](./pipeline-graph) per the canonical vocabulary.
* **Refreshed** to the shipped W1–W7 state: new [api-v1](./control-plane/api-v1.md) +
  [queries](./control-plane/queries.md) + [security module](./modules/security.md) concepts;
  [control-api](./control-plane/control-api.md), [jobs](./control-plane/jobs.md) (async 202+runId),
  [events-metrics](./control-plane/events-metrics.md) (legacy-usage metric),
  [auth-security](./editions/auth-security.md) / [editions-model](./editions/editions-model.md)
  (`inspecto-security` is BUILT), [modules](./modules) + [agent](./agent/assist-agent.md)
  (agent-kernel → vendored kernel + eoiagent), and
  [component-registry](./components/component-registry.md) (widened writable kinds, ETag/ContentHash).

## 2026-06-28
* **Created**: Initial OKF v0.1 bundle for the Inspecto Java backend — [Overview](./overview.md),
  [Architecture](./architecture.md), the [Modules](./modules), [Engine](./engine), [Acquisition](./acquisition),
  [Control plane](./control-plane), [Flow-graph](./pipeline-graph) *(dir since renamed)*, [Components](./components), [Config](./config),
  [Editions](./editions), [Agent](./agent), [Build & run](./build-run), and [Gotchas](./gotchas) sets.
  Consolidated from `docs/PROJECT_NOTES.md`, the topic docs (`ADVANCED_GUIDE`, `EDITIONS`, `configuration`,
  `data_acquisition_framework`, `flow-graph-design`, `flow-live-execution-plan`, `performance`,
  `parsing-options-reference`, `delimited-grammar-design`, `plugins`, `BRANCHING`) and a source sweep of the
  `inspecto/`, `inspecto-connectors/`, `inspecto-agent/`, and `inspecto-agent-hosted/` modules.
