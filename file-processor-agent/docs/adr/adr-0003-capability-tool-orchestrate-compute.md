# ADR-0003: Two-level Capability → Tool; the LLM orchestrates & narrates, deterministic code computes & validates

**Status:** Accepted **Date:** 2026-06-04 **Deciders:** Kernel maintainers, UCC eng lead

## Context

UCC's current SPI is a flat `Skill` (`id() + tier() + run()`). The three agents share a deeper pattern: a high-level unit of work (answer a KPI, validate a document against a schema, support a decision) that internally calls deterministic, unit-tested computations. CxO's ADR-0001 makes this a hard rule — the LLM never calculates; every number comes from a tested tool. UCC's `SqlOracle` and CVVE's rule engine embody the same spine. A flat `Skill` cannot express the compute/orchestrate split structurally.

## Decision

Adopt a **two-level model**:

- **`Capability`** — a declarative, versioned unit the host dispatches. `CapabilitySpec` carries id, version, default model tier, confidence threshold, max execution time, required context keys, and allowed tools. A capability *orchestrates and phrases*.
- **`Tool`** — a deterministic, transport-free computation returning a `ToolResult` carrying typed `Evidence` (ADR-0004).

The LLM (via `ModelProvider`) decides which tool to call with what parameters and narrates the result; it **never invents a number a tool did not return**. RAG/retrieval supplies qualitative context only. This generalizes UCC `Skill → Capability` and makes the spine — *LLM narrates, code computes* — a structural property of the kernel rather than a convention.

**Tools are transport-free.** `Tool` is a plain **in-process Java interface** (`spec()` + `invoke(args, ctx)`); ring-1 takes **no MCP/ADK dependency** (preserving the zero-dep core, ADR-0001). Standardized/remote tool transport — MCP, Google ADK — is deferred: when a consumer needs it, it arrives as an additive **ring-2 adapter** (e.g. `agent-tool-mcp`) that wraps `Tool`, so the in-process interface is the shim point. This mirrors the CxO agent's own "native tools, defer MCP" decision. Adopting MCP in the core now was considered and rejected: it adds a dependency and a transport concern before any consumer needs them.

## Options Considered

### Option A: Keep a flat Skill that freely mixes model calls and computation

| Dimension | Assessment |
|---|---|
| Simplicity | High — one concept |
| Correctness | Weak — no enforced compute/narrate split |
| Auditability | Poor — figures not necessarily sourced |

**Pros:** minimal. **Cons:** cannot express CxO ADR-0001; no provenance discipline; can't attach per-unit timeout/eval cleanly.

### Option B: Capability orchestrates, Tool computes; results carry Evidence (chosen)

| Dimension | Assessment |
|---|---|
| Correctness | High — math is in tested tools |
| Auditability | High — every figure carries provenance |
| Reusability | High — tools shared across capabilities |

**Pros:** encodes the spine; testable tools; provenance built in; declarative descriptor enables timeouts and golden-eval; tools reused across capabilities. **Cons:** two concepts instead of one; authors must route computation through tools (acceptable, even desirable — same trade as CxO ADR-0001).

## Trade-off Analysis

Slightly more structure versus a trustworthy, auditable, testable agent. For CxO it is non-negotiable; for UCC and CVVE it is a clear net benefit (testable oracle/rule engine, citable answers). The "two concepts" cost is conceptual, not runtime.

## Consequences

- **Easier:** unit-testing the math; citing figures; per-capability timeout and golden-eval; reusing tools.
- **Harder:** every quantitative answer needs a real tool — no shortcuts via prompting.
- **Revisit:** the `CapabilitySpec` descriptor fields may grow; the orchestrate/compute split does not. Add a ring-2 MCP/ADK adapter if a consumer needs remote tools — supersede only if that changes the `Tool` contract.

## Action Items

1. `Capability` / `CapabilitySpec` and `Tool` / `ToolSpec` / `ToolResult` in ring-1 (`…agent`, `…tool`).
2. Capabilities read their tier via `ctx.effectiveTier(spec.defaultTier())` (see ADR-0005).
3. Document the "no unsourced figures" contract on `Capability`; enforce at runtime via GroundingGuard (ADR-0006).
4. `Tool` stays an in-process interface; **no MCP/ADK deps in core**. Note `agent-tool-mcp` as a future ring-2 adapter.
