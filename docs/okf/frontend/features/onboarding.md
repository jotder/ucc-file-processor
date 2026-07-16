---
type: Feature
title: Stream & Reference Onboarding
description: Guided, resumable authoring of a data origin — a stage rail over the server-held Stage-1 pipeline draft.
resource: inspecto-ui/src/app/modules/admin/catalog/onboarding/onboarding-shell.component.ts
tags: [feature, onboarding, catalog, acquisition, pipeline]
timestamp: 2026-07-16T00:00:00Z
---

# Stream & Reference Onboarding

Route `/catalog/onboard/:name(/:stage)` (one matcher route — the shell survives stage navigation),
entered from the Catalog Streams/References tabs' **Onboard Stream / Onboard Reference** header CTA
(lens-gated `canAuthorWorkbench`). The create dialog asks the minimum (kind toggle + name +
optional description; directories derive from the space convention under a collapsed Advanced) and
writes a minimal `active: false` pipeline draft + registers it — **the server-held config IS the
draft** (shift-handover safe; no wizard state is ever stored). Vocabulary: [GLOSSARY](../../../GLOSSARY.md)
§2 *Onboard*; ⛔ never "wizard" in copy.

## The stage rail (kind-aware, jumpable — not a locked stepper)

- **Stream:** Collection → Parsing → Schema & Mapping → Enrichment *(optional)* → Dataset & Go-live.
- **Reference** (`produces: reference` written at create): Collection → Parsing → **Keys & Load**
  (the SAME schema pane, plus an honest full-replace load-policy note) → **Publish** (bindable-by-name
  note `ref: <normalized-id>`).

Per-stage readiness chips are **computed from the config blocks on every read** (Not configured /
Configured / Validated — Validated is session-only, from a passed sample test); lifecycle badge =
Draft → Ready (all required stages configured) → Live (`active: true`). Resume lands on the first
incomplete stage. Discard = `DELETE /config/pipeline/{name}` (refused while active).

## Sample-as-thread

One captured sample (file ≤256KB or paste, session-held) threads through the stages: raw → parsed
(`POST /config/preview/parsing`, real DuckDB) → cast-checked (`POST /config/preview/schema`,
TRY_CAST). A new sample or re-parse invalidates the downstream hops. The schema pane DERIVES its
fields from the parsed columns (frontend-aware selectors: positional for delimited/fixedwidth,
verbatim key for json/text_regex) and offers only the four honestly-cast types
(VARCHAR/DOUBLE/DATE/TIMESTAMP — exactly what `TransformCompiler.direct()` casts).

## Enrichment stage (Streams, optional)

Opt-in pane authoring the companion `EnrichmentConfig` (`<pipeline>_enrich`): reference bindings
(**by-name first** — the picker offers only pipeline-produced Reference Datasets, minus self — with
a file-path fallback) + CodeMirror transform SQL. Wiring is derived, never asked: input = this
pipeline's Stage-1 output, `triggers.on_pipeline` = the engine-normalized id
(`name.toLowerCase().replace(' ','_')` — what `BatchEvent.pipeline()` carries), output = the
space's `enriched/` convention. **Every save re-registers** (`POST /enrichment`) because
enrichments do not hot-reload by mtime; a register failure downgrades to a warning (the file is
saved; it loads on restart).

## Seams & gotchas

Backend seams: [onboarding-authoring](../../backend/control-plane/onboarding-authoring.md). The
create dialog silently derives the full dir convention (`status_dir` et al — without it the Runs
history stays empty) and `processing.duplicate_check` (the collector-level `duplicate:` block is a
no-op on the legacy local poll path — without markers the same file re-ingests every cycle).
Catalog list rows show Draft/Live from `attrs.active` (References included, via the produced-origin
graph attrs); "Ready" is visible in the shell header only. Offline via the `onboarding.handler`
mock (config write/read/delete, both previews, register pair).
