# Assist Agent Improvement Plan & Spec

**Session scope** · branch `4.x` · 2026-06-12
**Status 2026-06-12**: Workstream R (tiers 1+2) and Workstream A **implemented and
smoke-tested** (settings persisted as `assist-settings.properties` — a pragmatic deviation
from the `assist.toon` spec idea; endpoints `GET/POST /assist/settings` +
`POST /assist/settings/test` live; hosted module `inspecto-agent` built;
settings screen at `/settings/models`). Workstream B: **B1 done** (SkillInputs extraction,
AssistTunables knobs incl. reactor queue + confidence threshold, TimeoutModelProvider hard
deadline, doc-RAG failure guard) + **B3 partial** (tunables, timeout, diagnoser-fallback
tests; FailureReactor overflow was already covered). **B2 done**: AssistMetrics audit-sink decorator
(per-intent calls/ok/unavailable/repaired/avgMs/lastConfidence) behind
`GET /assist/metrics`; the `[ASSIST]` log line now carries `repaired=` and `tier=`.
**B3/B4 closed with findings**: narrative window edges are benign by
construction (`Window.of` is lenient — null ⇒ unbounded, inverted ⇒ empty);
diagnose-and-alert pipeline grounding was already sound (validator checks against
catalog-resolved names at request time) — both review "gaps" overstated.
OperationalTables now warns once per table on ledger/schema drift instead of silent
NULLs. Intent-ID registry skipped deliberately (churn > value). **Workstream B complete**;
the only remaining arc is the deferred alert execution engine (B5).
**Modules touched**: `file-processor-agent`, new `inspecto-agent`, `file-processor` (ControlApi seam only), `inspector-ui`.

---

## Workstream R — Rebrand: UCC File Processor → **Inspecto**

Product rename ahead of the feature work so all new code/config lands under the new name.

### R1. Scope tiers (cheapest → most invasive)

1. **Display-name tier (low risk, do first)**
   - Inspector UI: app title, header/sidebar branding, page `<title>`, manifest,
     login/about strings in `inspector-ui/` (template logo slot is currently empty —
     drop an Inspecto logo into `public/images/logo/`, which also fixes the known
     broken-logo note for the dense rail).
   - Docs: `README`, `docs/**`, packaging script banners (`package.ps1`), CLI `--help` /
     startup banner text in the core.
   - HTTP server identification strings / status report product field, if any.
2. **Artifact tier (medium)**
   - Maven: rename artifactIds (`file-processor` → `inspecto-core` or keep module dirs
     and change `<name>`/`<description>` only — **decision below**), fat-JAR final name,
     version stays `4.1.0-SNAPSHOT`.
   - npm package name in `inspector-ui/package.json`.
3. **Identifier tier (high churn — recommend deferring)**
   - Java package `com.gamma.*` namespaces, `ucc`-prefixed env vars
     (`UCC_ASSIST_PROVIDER` → `INSPECTO_*`), class names like `UccAssistAgent`,
     config keys, repo/directory rename, CI workflow names.

### R2. Recommendation for this session

Do **tier 1 fully + tier 2 names/descriptions**; keep Maven artifactIds, Java packages,
and the repo directory unchanged for now (rename them in a dedicated commit later — they
ripple through CI, `~/.m2` consumers, and the agent-kernel dependency docs). New code
written in Workstreams A/B uses Inspecto-neutral naming (env vars get `INSPECTO_` with
`UCC_` fallback).

### R3. Open decisions

- Confirm spelling: **Inspecto** (as given) vs *Inspekto*/*Inspector*.
- Naming collision: the UI module is already `inspector-ui` and the console is called
  "Inspector operator console" — under the rebrand the *product* is Inspecto and the
  console screen keeps "Inspector" or becomes "Inspecto Console"? (Plan assumes
  **Inspecto Console**.)
- Logo asset: none exists; need a provided SVG/PNG or a simple wordmark placeholder.

---

## Sequencing rationale

The local-Ollama environment is a bottleneck for exercising the skills, so **Workstream A
(model configuration & routing, hosted-first)** comes first: it unblocks real testing of
everything else. **Workstream B (hardening/improvements)** follows, validated against a
hosted model.

---

## Workstream A — Model configuration & routing (hosted-first)

### A0. Current state

- Kernel SPI: `ModelRouter` → `ModelProvider` (tier-aware: SMALL / MEDIUM / LARGE).
- Single implementation today: `agent-provider-ollama` (kernel 1.0.0), resolved in
  `UccAssistAgent` no-arg constructor via `OllamaModelProvider.fromEnvironment()` —
  env-only, no runtime configuration, no hosted option.
- `langchain4j-core` 1.15.1 is already a dependency of `file-processor-agent`.

### A1. Settings model

New record `ProviderSettings` (in `file-processor-agent`, `com.gamma.agent.model`):

```
provider      : enum { ANTHROPIC, OPENAI, GEMINI, OLLAMA, GEMMA }
environment   : derived — HOSTED for first four, LOCAL for last two
modelByTier   : Map<Tier, String>   // SMALL / MEDIUM / LARGE → model id
baseUrl       : String?             // LOCAL only; defaults 11434 (ollama) / 8080
apiKeyRef     : String?             // HOSTED only; see security note — never the key itself
timeoutSeconds, maxRetries          // sane defaults (60s, 2)
```

Default tier maps:

| Provider | SMALL | MEDIUM | LARGE |
|---|---|---|---|
| Anthropic | claude-haiku-4-5 | claude-sonnet-4-6 | claude-opus-4-8 |
| OpenAI | gpt-4o-mini | gpt-4o | gpt-4o (or o-series) |
| Gemini | gemini-flash | gemini-flash | gemini-pro |
| Ollama | (current 2–3B) | (current 7B) | (current 14B) |
| llama.cpp | served model | served model | served model |

*(Hosted ids verified against current provider docs at implementation time; Anthropic ids
confirmed via the claude-api reference before coding the adapter.)*

**Persistence**: `assist.toon` in the workspace config dir, loaded via `ConfigSource`,
with env-var override (`UCC_ASSIST_PROVIDER`, …) so air-gapped/ops deployments keep
working without the file. A `ConfigSpecs#assist()` spec validates it like every other
config type (and makes it visible to `suggest-config`).

**Secrets**: the `.toon` stores only `apiKeyRef` — the *name* of an env var holding the
key (e.g. `ANTHROPIC_API_KEY`). The settings endpoint accepts a raw key write-only and
maps it to an in-memory credential store + optional OS env guidance; `GET` never echoes
keys (masked `set/unset` flag only). No plaintext keys on disk, in logs, or in audit.

### A2. Provider factory & adapters

- `ModelProviderFactory` (in `file-processor-agent`): `ProviderSettings → ModelProvider`.
  - `OLLAMA` → existing `agent-provider-ollama`.
  - Everything else → resolved via **ServiceLoader SPI** `HostedProviderPlugin`
    (provider enum → langchain4j builder), implemented in the new module.
- New Maven module **`inspecto-agent`** (keeps the air-gap guarantee:
  default packaging excludes it; hosted options only light up when the jar is present):
  - deps: `langchain4j-anthropic`, `langchain4j-open-ai`, `langchain4j-google-ai-gemini`.
  - One adapter class `LangChain4jModelProvider implements ModelProvider` wrapping a
    langchain4j `ChatModel` per tier.
  - **llama.cpp** ride the OpenAI-compatible client (custom baseUrl) — no
    extra SDKs.
  - Capability flag: factory exposes `availableProviders()` so the UI can grey out
    hosted entries when the jar is absent.

### A3. Live re-registration

`UccAssistAgent` gains `reconfigureModels(ProviderSettings)`:
- builds the new provider, swaps the `ModelRouter` behind a volatile handle
  (skills resolve the router per-request via `UccAgentContext`, so no skill changes),
- closes the old provider,
- emits an audit event (`AssistReconfigured`, keys-only: provider, tiers — no secrets).

### A4. HTTP surface (ControlApi)

| Endpoint | Purpose |
|---|---|
| `GET  /assist/settings` | current settings (key masked) + `availableProviders` |
| `PUT  /assist/settings` | validate (spec oracle) → persist `assist.toon` → live re-register |
| `POST /assist/settings/test` | round-trip a 1-token prompt on each configured tier; returns per-tier ok/latency/error |

Follows the existing config-save + live-registration pattern from `1a1c6b5`.

### A5. Inspector UI settings screen

New route `Assist → Model Settings` in `inspector-ui` (gamma shell, Material/Tailwind):
1. **Provider** dropdown, grouped *Hosted* (Claude, Gemini, ChatGPT) / *Local*
   (Ollama, llama.cpp); hosted entries disabled with tooltip when hosted jar absent.
2. **Dynamic credential field**: masked *API Key* (hosted, with show/hide toggle,
   write-only — placeholder shows `••• (set)` when configured) vs *Local Base URL*
   (local, pre-filled per provider default).
3. **Model per tier**: three selects (SMALL/MEDIUM/LARGE) seeded from the default tier
   map, free-text allowed.
4. **Test connection** button → `POST /assist/settings/test`, per-tier result chips.
5. Save → `PUT`, toast on success; Vitest specs for the form logic + service.

### A6. Acceptance criteria

- With only env `ANTHROPIC_API_KEY` set + hosted jar on classpath: select Claude in the
  UI, test passes, `kpi-to-sql` E2E returns a validated draft via the hosted model.
- With hosted jar removed: build green, UI shows only local providers, behavior identical
  to today (abstain-safe with no model).
- No secret ever appears in `assist.toon`, logs, audit events, or GET responses.
- All existing 436 Java tests + Vitest suite stay green.

---

## Workstream B — Prioritized improvements (from the module review)

### B1. Hardening & consistency pass (P1)

1. **Shared input helpers**: extract duplicated `firstNonBlank` / `str` / `orDefault` /
   `asBool` from all 6 skills into `com.gamma.agent.skill.SkillInputs`.
2. **`AssistSettings` tunables** (folded into the same `assist.toon`): repair rounds
   (3), max neighbors (8), doc snippets (3), confidence threshold (0.5), reactor queue
   capacity (1024) — config/env-overridable with current values as defaults.
3. **Timeout enforcement**: `Capability` specs declare 60s but nothing enforces it —
   wrap model + oracle calls in a timeout executor in the orchestration seam; timeout →
   clean `UNAVAILABLE`, audited.
4. **Error-handling consistency**: catch doc-RAG retrieval failure in
   `ExplainEntitySkill` (degrade to catalog-only grounding); document/normalize the
   converter throw in `ReportNarrativeSkill`.

### B2. Observability — rejected-draft trail (P2)

- Audit events for repair-loop retries (`AssistRepairAttempt`: intent, round,
  oracle-finding code) and abstentions (`AssistAbstained`: intent, confidence,
  threshold). Keys-only, PII-safe, same `LoggingAuditSink` format.
- `GET /assist/diagnoses` style counter surface (or extend status report) so operators
  can see retry/abstain rates per skill — prerequisite for tuning thresholds per B1.2.

### B3. Test gaps (P2, locks in B1/B2)

- Integration test: escalation policy actually abstains end-to-end (low-confidence fake
  model → HTTP `UNAVAILABLE`).
- `FailureReactor` overflow: flood > queue capacity → drops logged, no blocking.
- `ModelDiagnoser` fallback: injected throwing model → heuristic diagnosis returned.
- `ExplainEntitySkill` null-catalog; `ReportNarrativeSkill` window edge cases
  (null window, `to < from`).
- New: factory/adapter unit tests (fake langchain4j model), settings endpoint
  round-trip, secret-masking assertions.

### B4. Smaller fixes (P3, opportunistic)

- `DiagnoseAndAlertSkill`: re-resolve `onPipeline` at draft time, not set-membership only.
- `OperationalTables`: warn (once) on column/key misalignment instead of silent NULLs.
- Central intent-ID registry (constants class) replacing scattered string literals.
- Drop the unused `AlertRuleValidator#asSet()`.

### B5. Deferred (separate arc — not this session)

- **Alert execution engine** for `diagnose-and-alert` drafts: touches the core engine
  (scheduler, metric evaluation, Inspector surfacing) and needs its own design decision.

---

## Execution order for this session

0. **R** rebrand (tier 1 + tier 2 names) — first, so A/B code lands under the new name.
1. **A1–A4** backend: settings model, factory, hosted module, endpoints (+ tests).
2. **A5** inspector-ui settings screen (+ Vitest).
3. **A6** verify hosted-first E2E (Claude) — this is the user's primary test path.
4. **B1** hardening pass → **B3** tests → **B2** observability → **B4** small fixes,
   in that order, as session time allows.

**Standing guardrails**: `file-processor/pom.xml` never staged; no commit/push without
explicit ask; core module stays lean (all new deps confined to agent / agent-hosted
modules); never stage `run-adjustment.bat`.
