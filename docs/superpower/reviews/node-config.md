# Review sheet — Node config dialog (Pipelines / Workbench)

**Wave:** 1 (Builder/Workbench) · **Date:** 2026-07-02 · **Files:**
`modules/admin/pipelines/node-config.dialog.ts` (+spec) + new `node-attributes.ts` (+spec)

Third Wave-1 SchemaForm conversion. `NodeConfigDialog` is the generic "Configure Processor" popup for
**every non-parser node** (collectors, transforms, sinks) — the last free-form config surface in the
pipeline editor, and a direct instance of the mandate's "components have more/fewer fields than the
function needs."

## R1 — Glossary

No new concepts. **Step** config, **Component** ref binding — canonical terms unchanged.

## R2 — Attribute audit

`node-attributes.ts` declares an `AttributeSpec[]` per common node type, tiered
`required | optional | advanced`, drawn from the seeded sample pipelines + `flow-graph-design.md`:

- `collector.file` (include req; exclude/recursive opt; min-age adv), `collector.database` (query req;
  watermark opt; fetch-size adv), `collector.stream` (topic req; group opt; batch adv)
- `transform.filter` (predicate req), `transform.route` (mode req; route-column opt),
  `transform.aggregate` (group-by + aggregations req), `transform.alert` (condition + severity req)
- `sink.file` (format req; partition-by opt; compression adv), `sink.database` (table + mode req;
  key-columns opt, **dependsOn mode=upsert**)

Marked best-guess in the file header: authored-flow node config isn't firmly specced server-side (shapes
live in test strings, `FEATURE_INVENTORY.md` §G), so these will firm up as the backend does. A type with
**no** entry (`transform.record`, plugins, anything new) is explicitly supported — see R4.

## R3 — UX pass

Known types now get a right-sized form (required fields visible, the rest behind Optional/⚙ disclosure)
instead of a flat key/value grid where the user had to *know* the keys. name / description / component-ref
binding / test all unchanged.

## R4 — Reuse pass + the escape hatch (the important design call)

Reuses `<inspecto-schema-form>`. The free-form key/value editor is **kept**, not deleted — as a collapsed
**"Additional config"** section. This is deliberate: it makes the conversion strictly non-lossy.

- Type **with** a schema: schema-form is primary; the free-form editor is collapsed and holds only keys
  outside the schema (opens automatically if any such keys exist on load).
- Type **without** a schema (plugin/unknown): the free-form editor is the primary surface, open by default,
  labeled just "Config" — byte-for-byte the old behavior. Nothing regresses for un-schema'd types.

## R5 — Logic extraction

The per-type schema table is the pure, data-only `node-attributes.ts` (framework-free, vitest-tested via
`nodeAttributesFor` + `byTier`). The dialog keeps only the load-time config split (schema-known keys →
schema-form initial; the rest → free-form rows) and the save-time merge (coerced schema values, then
free-form rows for anything outside the schema).

## R6 — Mock contract

Unchanged — grammar/transform/sink component CRUD + `testNode` already served by the unified mock store;
node config is embedded in the authored-pipeline document, no new endpoint.

## R7 — Interview / decisions made

1. **Tier + key names per type are my best guess** (R2) — the same caveat as parser-config. Flag any that
   feel wrong once real backend node config lands.
2. **Declared defaults now persist**: saving a `sink.file` writes `compression: snappy` even if the user
   never touched it (it's the declared default). Consistent with how every SchemaForm behaves; noting it
   because it means saved configs get slightly more verbose than the hand-authored originals. If that's
   undesirable, the fix is to omit default-valued keys on save — a one-line change, deferred pending a call.
3. **`transform.record` intentionally has no schema** — its config is arbitrary field-derivation rules with
   no fixed shape, so free-form is the honest surface for it today.

**Answered (batched to product owner, 2026-07-02):** #2 — **keep persisting declared defaults** on save
(consistent with every SchemaForm; configs stay explicit/self-documenting). The "omit untouched defaults"
alternative was declined. #1/#3 stand as recorded assumptions. Also confirmed wave-wide: the **Business
lens is read-only** across the Workbench (authoring gated to Builder) — wiring lands with the lens shell in
Wave 2, so no gating code this wave.

## R8 — Verify (evidence)

- **Live smoke** (`:4204`): opened the `cdr_ingest` pipeline's `write` node (`sink.file`) via the editor.
  Confirmed the schema-form renders with `Format` required, `partition_by` under Optional, `compression`
  behind the gear, plus a collapsed "Additional config". The seeded config
  `{format: PARQUET, partition_by: event_date}` split correctly into the schema-form (values preserved);
  Save round-tripped `{format: PARQUET, partition_by: event_date, compression: snappy}` back onto the node.
  No new console errors.
- **Automated**: `lint:tokens` ✓ · prod `build` ✓ · `test:ci` **474 passed / 0 failed / 5 skipped**
  (baseline 466/0/5). Existing `node-config.dialog.spec.ts` cases (component picker, ref binding) pass
  unchanged; new cases cover the schema/free-form split, the merge on save, and the no-schema fallback.

**Definition of Done: met** (with the default-persistence note in R7 recorded for a product call).
