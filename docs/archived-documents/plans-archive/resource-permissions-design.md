# SEC-7(b) — Per-resource `permissions[]` (design of record)

> Status: **SHIPPED** (first slice, 2026-07-07) · Owner: backend · Parent design:
> [`api-contract-design.md`](api-contract-design.md) §8 ("permission derivation") · Groundwork:
> [`rbac-groundwork.md`](rbac-groundwork.md)

## 1. What §8 asks for

The v1 envelope's `permissions` array should be computed **per resource** as
*subject's grants ∩ resource state* — e.g. no `execute` on an already-disabled job, no `edit` without
`canAuthorWorkbench`. W6 shipped a stated deviation: the array was the Subject's **session-wide**
capability set on every response.

## 2. Decisions

1. **Vocabulary stays capability verbs** (guideline 13) — the same names the UI's Lens/Session signals
   already use (`canAuthorWorkbench`, `canOperateRuns`, `canTriageRequirements`, …). No second verb
   vocabulary (`edit`/`delete`) is introduced; a capability *is* the verb family for a bounded context.
2. **The refinement is an envelope concern, applied per response** — a route handler that serves a
   *single resource* declares the resource-applicable capability set on the exchange
   (`ApiContext.resourcePermissions(ex, Set)`); `Envelope.success` then emits
   `subject.capabilities() ∩ applicable`. No attribute declared → session-wide array, exactly as before
   (lists, non-resource endpoints, and every un-migrated route are unchanged).
3. **v1-only, additive.** Legacy (unversioned) responses stay byte-for-byte — the refinement lives in
   the envelope, which legacy responses never get. No resource DTO grows a `permissions` field, so list
   shapes (e.g. `JobView`) are untouched.
4. **Resource state that makes an action impossible removes the capability from the array.** The array
   answers the UI's only question — "which action affordances do I render for *this* resource?" —
   fail-closed: showing fewer verbs than the session holds is always safe.
5. **Personal stays absent-by-absence**: no Subject ⇒ no `permissions` key at all (unchanged W6 rule).
   The refinement only ever *narrows* an authenticated array.

## 3. Where it applies today (first slice)

| Route | Applicable set (∩ session grants) |
|---|---|
| `GET /components/{type}/{id}` | `{canAuthorWorkbench}` — the only verbs on a registry component |
| `GET/POST/PUT /expectations*` single-resource responses | `{canAuthorWorkbench}` |
| `/requirements` single-resource responses | **status-dependent**: `submitted`/`accepted` → `{canTriageRequirements}` (decide / deliver still possible) · `rejected`/`delivered` → `{}` (terminal — nothing left to do) |

The §8 disabled-job exemplar is already enforced *harder* than a permission array: `JobService` never
registers a disabled job, so its trigger 404s. When a single-job `GET /jobs/{name}` route lands, it
declares `{canOperateRuns}` (∅ when disabled) the same way.

## 4. How a context opts in (the pattern)

```java
// in a RouteModule handler that returns ONE resource:
ApiContext.resourcePermissions(e, Set.of("canAuthorWorkbench"));
return resourceDoc;
```

State-dependent contexts compute the set from the loaded resource before declaring it (see
`RequirementRoutes.applicable(status)`). Nothing else changes: capability *enforcement* stays
`ApiContext.withCapability` / `requireCapability` on the write routes — the array is an affordance
signal, never the security boundary.

## 5. Explicitly out of scope

- **Data-scoped grants** (a fraud analyst sees fraud cases) — row-level filters inside the owning
  context's queries; deferred pending the product decision (rbac-groundwork §4 open Q2). SEC-7(d).
- **Per-resource ACLs / ownership** (a `permissions[]` stored *on* the resource) — nothing in the
  product requires per-object grants yet; revisit with Enterprise per-tenant ABAC (SPC-5).
- **List-row `permissions[]`** — would change legacy DTO shapes; take up only when a UI list actually
  needs per-row affordances the status field can't already derive.
