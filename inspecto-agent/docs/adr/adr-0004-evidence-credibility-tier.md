# ADR-0004: Unified Evidence + CredibilityTier provenance model

**Status:** Accepted **Date:** 2026-06-04 **Deciders:** Kernel maintainers, UCC eng lead

## Context

UCC has a thin `Citation`. CxO needs credibility-tiered provenance (AUTHORITATIVE / INDICATIVE / USER_PROVIDED / ASSUMPTION) feeding its reconcile-don't-average rule (CxO ADR-0002). CVVE needs per-field, system-vs-human confidence on validation results. These are the same idea at three fidelities. Three incompatible provenance types would block reuse and make any cross-app reasoning (or a shared GroundingGuard) impossible.

## Decision

One ring-1 record carries provenance everywhere:

```
Evidence(Object value, CredibilityTier tier, String tierLabel,
         String sourceRef, double confidence, Instant observedAt)
```

`CredibilityTier` is an **enum** (`AUTHORITATIVE, OFFICIAL, INDICATIVE, DERIVED, USER_PROVIDED, ASSUMPTION`) with a **`tierLabel` String escape hatch** for app-specific vocabularies that don't fit the enum at `0.x` (null/blank ⇒ use `tier`). `ToolResult` carries a list of `Evidence`; `AgentResult` consolidates evidence so any answer can cite originals. **Reconciliation logic** (CxO ADR-0002: normalize → anchor by credibility → show spread) is an app/ring-2 concern operating *over* `Evidence` — the kernel supplies the carrier, not the policy. `sourceRef` is a reference/locator, never the raw value (ties to ADR-0008).

## Options Considered

### Option A: Keep UCC's thin Citation

| Dimension | Assessment |
|---|---|
| Expressiveness | Low — no tiers/confidence |
| Reuse | Poor — CxO/CVVE fork it |

**Pros:** minimal. **Cons:** can't express credibility tiers or confidence; the other two apps would diverge.

### Option B: App-extensible sealed `CredibilityTier` interface from day one

| Dimension | Assessment |
|---|---|
| Flexibility | Maximal |
| Maturity | Premature — no 2nd consumer has exercised real vocabularies |
| Ergonomics | Harder to serialize/order/compare |

**Pros:** unlimited per-app vocabularies. **Cons:** premature abstraction (violates rule-of-three, ADR-0002); harder to serialize and compare.

### Option C: enum + String escape hatch now; revisit interface promotion at 1.0 (chosen)

| Dimension | Assessment |
|---|---|
| Simplicity | High — ordered, serializable enum |
| Coverage | Escape hatch handles outliers today |
| Future | Promotion deferred until evidence exists |

**Pros:** simple, serializable, ordered; `tierLabel` covers exotic cases; defers the hard call until a real 2nd consumer informs it. **Cons:** enum ordering may need revisiting at `1.0`.

## Trade-off Analysis

A fixed enum's simplicity (with an escape valve) versus unbounded flexibility we cannot yet justify. The rule of three says wait for CVVE/CxO to exercise real tier vocabularies before committing to an extensible type.

## Consequences

- **Easier:** one provenance type across all apps; citing, serializing, and comparing tiers.
- **Harder:** apps needing exotic tiers use `tierLabel` until `1.0`.
- **Revisit:** promote `CredibilityTier` to an app-extensible interface at `1.0`, once CVVE/CxO have exercised real vocabularies.

## Action Items

1. `Evidence` and `CredibilityTier` in ring-1 `…tool`.
2. Reconciliation stays app-side (or ring-2), operating over `Evidence`.
3. `sourceRef` is a locator, not a value (ADR-0008); record the revisit-at-`1.0` note on `CredibilityTier`.
