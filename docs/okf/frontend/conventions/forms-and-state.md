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
