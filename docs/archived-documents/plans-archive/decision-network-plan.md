# R5 — The Decision Network (Condition → Evaluation → Consequence[], AI as an engine)

> Approved 2026-07-06 (product owner chose **include Assist now**). Slice R5 of the Living Operational
> System roadmap (`docs/superpower/living-operational-system.md` §2/§5). Depends on R4 (the `emit-signal`
> consequence writes into the signal ledger). UI-only / mock-first / independently shippable.

## Thesis

The three canonical rule kinds each hard-wire one consequence today:

| Kind | Today | Consequence (hard-wired) |
|---|---|---|
| **Expectation** | full CRUD + evaluate; own store `expectation` | raises an Incident + `EXPECTATION_FAILED` signal (R4) |
| **Alert Rule** | read-only ops (definitions in `alert-rule`) | fires an Alert (`ALERT_FIRED` signal) |
| **Decision Rule** | full CRUD + simulate; own store `decision-rule` | **already** a `consequences[]` array (`route/tag/quarantine/drop`) |

The `rule` ComponentKind is an atomic **no-op shell** (`platform-kinds.ts`), and none of the three are real
ComponentKinds. R5 unifies them on `Condition → Evaluation → Consequence[]` where a **Consequence** is a typed
action, promotes **`decision-rule`** to a first-class ComponentKind (the flagship — it already has the shape),
and plugs the AI Assist in as a **decision engine** that proposes the *same* Consequence objects behind a
human-approval gate. Architecture unchanged; sophistication grows.

## Part A — the Consequence model (`inspecto/decision/consequence.ts`, pure)

Widen Decision Rule's `DecisionConsequence` into the shared, typed `Consequence` — **additive**, keeping the
`action` field + `destination` so the existing form/handler/seeds barely change:

```ts
export type ConsequenceType =
  | 'route' | 'tag' | 'quarantine' | 'drop'                 // record-routing (today's DecisionConsequenceAction)
  | 'emit-signal' | 'create-alert' | 'start-job'            // platform actions (executed via the Execution/Signal networks)
  | 'trigger-pipeline' | 'render-widget' | 'generate-report' | 'invoke-api';

export interface Consequence {
  action: ConsequenceType;
  destination?: string | null;                  // route⇒branch · tag⇒value · quarantine⇒reason (unchanged)
  target?: { kind: string; id: string } | null; // NEW — the component a platform action acts on
  params?: Record<string, unknown>;             // NEW — action params (emit-signal type/severity/message · invoke-api url · report name)
}

export function consequenceRefs(cs: Consequence[]): Ref[];   // start-job→job, trigger-pipeline→pipeline, render-widget→widget: rel:'invokes'
export function describeConsequence(c: Consequence): string; // human summary for the ledger/UI
export const CONSEQUENCE_TARGET_KIND: Partial<Record<ConsequenceType, string>>; // which kind each action targets
```

`Consequence` supersedes `DecisionConsequence` (aliased for back-compat). `RefRel` gains **`invokes`** (added in R4's
edit). Consequences that name a component (`start-job`/`trigger-pipeline`/`render-widget`) become **`invokes`** edges
in the metadata graph — a decision rule's lineage.

## Part B — `decision-rule` ComponentKind (`modules/admin/decision-rules/decision-rule.kind.ts`)

House pattern (guarded `registerKind`, like `query.kind.ts`): `id:'decision-rule'`, `wiring:'none'`,
`allowedPartKinds:[]`, `config.validate/create`, `authoring:{editorKey:'decision-rule'}`,
`exec:{runnerKey:'decision-rule'}`, and `deriveRefs` = **target** (`{kind: targetType, id: target, rel:'binds', via:'target'}`)
+ **`consequenceRefs(consequences)`** (invokes edges). `decisionRuleRefs()` lives in `component-model/refs.ts`
(STRUCTURAL), consistent with R1–R3.

Decision Rule is an **own-store** artifact (`DECISION_RULES_COLL`, its own handler/service) — so it joins the three
R1 consumers the way **jobs/pipelines** did (not via the components store):
1. **Reuse-graph** (`catalog/registry.component.ts`): load `DECISION_RULES_COLL`, derive via `refsForComponent('decision-rule', …)`; `REGISTRY_KINDS` **replaces `'rule'` with `'decision-rule'`**; `EDITOR_PATH['decision-rule'] = '/decision-rules'`. Remove the atomic `RULE_KIND` shell if unreferenced.
2. **Bundle** (`transfer/bundle.ts`): add `'decision-rule'` as a `BundleKind` (ordered after pipeline/job), export/import reading `DECISION_RULES_COLL`; `refsOf` delegates to the registry.
3. **Delete-protection** (`mock/integrity.ts` + decision-rules handler): a RefRule over `DECISION_RULES_COLL` so deleting a **pipeline/job/widget a decision rule targets or invokes** 409s.

## Part C — the execution seam (`inspecto/mock/decision.ts`) — the R4↔R5 link

`executeConsequences(store, space, rule): ExecutedConsequence[]` runs a rule's consequences through the platform
(mock-first), returning `{action, status:'executed'|'skipped', detail}[]`:

- **`emit-signal`** → `emitSignal(...)` (a `DECISION` signal; `params.type/severity/message`). ← the R4 ledger.
- **`create-alert`** → `emitSignal(ALERT_FIRED …)` (fans out).
- **`start-job`** → `recordRun(target job, 'DECISION', 'RUNNING'…)` + a `JOB_STARTED` signal.
- **`trigger-pipeline`** → a `PIPELINE_TRIGGERED` signal (mock).
- **`render-widget` / `generate-report` / `invoke-api`** → a descriptive stub signal (no real side effect in mock).
- **`route/tag/quarantine/drop`** → record-routing, part of the simulation count (no platform side effect).

New route **`POST /decision-rules/{name}/apply`** executes + returns the executed list; the Decision Rule pane gains
an **Apply consequences** action that runs it and shows the resulting signals — visibly writing to the Signal Ledger
(the R4↔R5 proof). `simulate` stays the pure preview.

## Part D — Assist as a decision engine (the "include Assist now" scope)

- **`AssistResult.data.consequences?: Consequence[]`** — the AI's proposal, same objects a rule holds.
- New intent **`propose-decision`** (join `ASSIST_INTENTS`): the mock assist handler returns a deterministic
  `Consequence[]` proposal for a described situation (e.g. "quarantine + alert on high-cost spikes").
- **Assist panel**: when a result carries `consequences`, render them (via `describeConsequence`) with a
  **human-approval gate** — an **Apply** action that either **executes** them (through the same `/…/apply` path) or
  **creates a Decision Rule** from them (opens the Decision Rule form pre-filled). Approval *is* the consequence gate;
  the AI proposes, the human commits. This proves "AI is a decision layer producing the same consequence objects" —
  architecture untouched.

## Seeds / vocab / docs

- **Seed** (`operations.seed.ts`): extend `quarantine_high_cost` with platform consequences (`emit-signal` +
  `create-alert`) and add one rule with a `start-job`/`trigger-pipeline` target → demos the unified model + `invokes`
  edges in the reuse-graph. Bump `MOCK_STORE_KEY` **v12 → v13**.
- **GLOSSARY**: adopt **Consequence** (typed action a decision engine produces) + **Decision Engine** (anything that
  turns signals/conditions into consequences: rules today, AI next) — §6 proposed → binding. Note the Expectation /
  Alert Rule mapping (their hard-wired consequences expressed in the vocabulary; full kind-promotion deferred).
- **Docs**: flip R5 → SHIPPED in `living-operational-system.md`; note in `metadata-network-design.md` (the `invokes` edge).

## Scope cuts (R2 "no abstraction without a second consumer")

- **Only `decision-rule` becomes a ComponentKind** this slice (it already has the consequence array — the natural
  fit, and the second consumer via execution + Assist proposal). Expectation + Alert Rule keep their evaluation;
  their consequences are **documented** in the vocabulary, not refactored (a later slice promotes them if warranted).
- Platform actions are **mock-executed** (signals + a job run); real job/pipeline invocation is backend scope.
- Assist proposal is a **deterministic mock** (no LLM call) — the seam + approval gate are the deliverable.

## Tests

`decision/consequence.spec.ts` (refs + describe + target-kind) · `decision-rule.kind.spec.ts` (validate + deriveRefs:
target + invokes) · `mock/decision.spec.ts` (executeConsequences emits the right signals — emit-signal/create-alert/
start-job) · `component-model/refs.spec.ts` (decision-rule cases) · `transfer/bundle.spec.ts` (decision-rule pulled
into closure with its invoked targets) · `decision-rules.handler.spec.ts` (apply route + 409 protecting an invoked
target) · assist panel spec (renders proposed consequences + apply gate) · decision-rule form spec (platform action
inputs).

## Verification (Definition of Done)

1. `npm run lint:tokens`  2. `npm run build`  3. `npm run test:ci` (baseline after R4; expect ~+20).
4. **Preview:** author a decision rule with `emit-signal` + `create-alert` + `start-job` consequences → **Apply** →
   the Signal Ledger shows the new signals + the bell fans out; the reuse-graph shows the rule's `binds`(target) +
   `invokes` edges; deleting an invoked job/pipeline 409s; export the rule → closure pulls its targets; Assist
   `propose-decision` returns consequences → **Apply / Create rule** works.
5. Commit `feat(ui): R5 …` (master-only per release-workflow); push only on explicit ask.
