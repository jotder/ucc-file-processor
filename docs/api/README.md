# `docs/api/` — the machine-readable v1 API contract

> Companion to [`../superpower/api-contract-design.md`](../superpower/api-contract-design.md) (the design)
> and [`../ADVANCED_GUIDE.md`](../ADVANCED_GUIDE.md) §10 (the as-built route reference). Enforced against
> the live server by `inspecto/src/test/java/com/gamma/control/ApiContractTest.java`.

## What lives here

- **[`openapi-v1.json`](openapi-v1.json)** — the OpenAPI 3.1 contract for the `/api/v1` surface:
  the shared transport components (Envelope, ErrorObject + ErrorCode catalog, Pagination, Signal, Ref)
  and the per-context business paths. Today it documents the **as-built exemplars** the transport spine
  (W1) pinned; each later worklog slice (W3+) adds its context's paths **before** implementing them —
  contract-first.
- **[`examples/`](examples)** — canonical example payloads, one file per named shape. The contract test
  validates each example against its schema's `required` tree, so examples cannot drift from the contract.

## Why JSON, not YAML (deliberate deviation from the design doc's `openapi-v1.yaml`)

The backend's lean SBOM has **no YAML parser** (Jackson databind only — adding one for a doc format
fails the "no heavy transitive deps" rule), and the Angular workspace imports JSON natively. One JSON
file keeps the contract machine-checkable **on both sides with zero new dependencies**. For the same
reason the contract is one file with per-context **tags** rather than per-context files: there is no
offline `$ref` bundler in the toolchain, and the contract test reads a single document. Split it only
if/when the file becomes unwieldy — that decision is reversible, the paths just move.

## Authoring workflow (contract-first)

1. **Design the endpoint in `api-contract-design.md` §6 terms** — one business capability, canonical
   GLOSSARY vocabulary, bounded-context tag.
2. **Add the path + DTO schemas here first**, reusing the shared components (`Envelope` wrapper via
   `allOf`, `ErrorResponse` for every non-2xx, `Pagination` in `metadata`). Give safe GETs an
   `x-probe {path, status}` so the contract test exercises them live.
3. **Add/refresh an example** under `examples/` if the shape is new, and register it in
   `ApiContractTest.EXAMPLES`.
4. **Implement the route** (the `endpoint` skill: gate order via `WriteGates`, real-HTTP test class).
5. `mvn -o clean test` — `ApiContractTest` fails if the doc, the examples, `ErrorCodes.java`, and the
   live surface disagree.

## Contract rules (binding, from the design doc)

- **Additive-only within v1**: never remove/rename a field or an ErrorCode; deprecate via
  `metadata.warnings` with a sunset date; breaking ⇒ `/api/v2`.
- Every response is `Envelope` (success) or `ErrorResponse` (non-2xx) — no bare payloads on v1.
- Every request/response carries `Correlation-ID`.
- DTO shapes only — no engine/entity leakage; secrets never on the wire (masked, `***`).
- `ErrorCode` enum ↔ `com.gamma.control.ErrorCodes` stay in lockstep (test-pinned in both directions).
