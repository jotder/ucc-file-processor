---
type: Convention
title: Forms & State
description: Reactive forms with inline errors; signal state; optimistic mutation for reversible edits.
resource: inspecto-ui/src/app/inspecto/api/optimistic.ts
tags: [forms, reactive-forms, signals, state, optimistic-ui]
timestamp: 2026-06-28T00:00:00Z
---

# Forms & State

## Forms

**Reactive only** (`FormBuilder`/`FormGroup`/`Validators`) with inline `<mat-error>` and
`markAllAsTouched()` on invalid submit. Template-driven `ngModel` is legacy — do not add it to new forms
(existing screens may still use it). Use typed `FormGroup`; reach for `this.form.controls.x` (not
`this.form.get('x')!`) to keep the typed-partial signatures.

### Dialog conventions (ui-design-review as-built)

* **Enter submits** schema-form dialogs (`(submitted)` on `<inspecto-schema-form>`).
* **Dirty guard**: wire editing dialogs through the shared `inspecto/dialog-dirty-guard.ts` so an
  accidental Esc/backdrop click can't drop edits.
* **Autocomplete attribute type**: entity-reference fields use the schema-form `autocomplete` type +
  `entity-option-loaders` (e.g. expectation target/refDataset, alert onPipeline) instead of free text.
  Column-of-a-target fields use `columnOptionLoader('<siblingKey>')` — a 1-row `/db/table` probe of the
  store the sibling field names, labels carrying the column type (expectation `column`/`refColumn`;
  the decision-rule when-clause fetches the same columns per target — no hardcoded record shape).
  Suggestions assist, they never constrain: a missing/unreadable store degrades to free text.
* **Tag chips**: the object-create dialog's tags are `MatChipGrid` chips suggested from the tag
  registry (`GET /tags`), assignee autocompletes over me + the mailbox's known assignees.
* **Name-at-save**: create dialogs ask config first and name/id last (two-step `saveForm` + `step`
  signal; ids pre-filled `<type>_<context>`; keep the config step `[hidden]` — not destroyed — so
  schema-form ViewChilds survive).
* **Initial focus**: `cdkFocusInitial` on the first field of every dialog (auto on schema-form's first
  required field).
* **Gotcha — `type="number"` must be static**: Angular's `NumberValueAccessor` selector matches the
  *static* `type="number"` attribute; a `[type]="…"` binding leaves the default (string) accessor
  attached, so the control emits `"4"` instead of `4`. The schema-form renders its `number` case with
  a literal `type="number"` for this reason — don't "simplify" it back into the shared text case.

## State

* **Signals** for component + shared service state; `computed()` for derived; `effect()` sparingly.
* `linkedSignal(() => source())` for "derived from a source but locally editable, resets when the source changes" (e.g. the SQL editor draft seeded from the generated SQL).
* RxJS for async pipelines + HTTP; unsubscribe via `takeUntilDestroyed(destroyRef)`.
* **No global store / NgRx.** Shared cross-cutting state lives in a root service exposing signals (pattern: `ConnectivityService`, `SpacesService`).

## Mutations

**Optimistic by default for reversible toggles/edits**: `optimisticMutate({apply, commit, reconcile,
rollback, onError})` (`inspecto/api/optimistic.ts`) — apply locally now, reconcile with the server result on
success (silent), roll back + toast on error. Reassign arrays (`rows = [...rows]`) so the grid re-renders.
Keep request→refetch for create/destroy or server-computed results.

The same rule holds for **bulk** verbs (the object-mail triage seam): patch every selected row + the open
detail to the expected post-state immediately, reconcile each row with the authoritative server object;
on any failure reload rather than snapshot-rollback (`forkJoin` fails fast — partial success makes a
rollback dishonest).
