# Inspecto — Executive Brief

> Audience: executives & sponsors · Status date: **2026-07-07** · ~3 minutes

## What it is

**Inspecto** is a lean, configuration-driven **data acquisition + management + BI + investigation
platform**: one ~90 MB self-contained artifact that runs on a laptop, an air-gapped server, or a
container — with **zero external runtime services**. One declarative config file onboards a data feed;
no pipeline project, no cluster, no glue scripts.

**It replaces four tool categories at once:** a NiFi-style collection/pipeline layer, a
Tableau/Superset-style self-service BI layer, a Jira-style ops incident workflow, and the surrounding
scripts (dedup, gap detection, retention, lineage). The wedge is **leanness *with* operability** —
built for regulated, air-gapped, and resource-constrained buyers where heavyweight stacks can't go.

## Why it wins

1. **One config onboards a feed** — hours, not sprints.
2. **Lean & self-contained** — tiny footprint, tiny dependency surface (a deliberate compliance asset).
3. **Operable by design** — every run crash-isolated and idempotent; everything audited: metrics,
   signals, immutable audit trail, managed incidents.

## Business model

**Editions are build flavors of one codebase** — no forks, one fix lands everywhere:

- **Personal** (free) — full platform, auth-free, local. The zero-friction adoption tier.
- **Standard** (paid — **the revenue gate**) — enterprise security: HTTPS, single-sign-on via the
  customer's identity provider (Keycloak/Okta/Entra), role-based access, attributed tamper-evident
  audit. **The security module shipped 2026-07-06**; hardening items remain before first customer.
- **Enterprise** (future, demand-gated) — clustering/shared state for horizontal scale.

## Where it stands (2026-07-07)

- **Delivered:** the full data plane (acquisition → parsing → pipelines → lakehouse), BI Studio with
  real persistence + live queries, investigation studios (Geo Map shipped; Link Analysis UI-complete),
  operations suite (signals, alerts → incidents → cases, audit), multi-tenant Spaces, a versioned
  public API (`/api/v1`), the Standard security module, and an embedded AI assistant that runs fully
  offline.
- **Release-gating remainder (the MUST list):** cloud object-storage connectors, JSON/regex parsing
  frontends, the data-quality Expectation engine, notification delivery, security hardening, and one
  live end-to-end pipeline verification. Full analysis: [`../REQUIREMENTS.md`](../REQUIREMENTS.md) §5.
- **Differentiator in flight:** an embedded-intelligence layer (AI with a governed autonomy ladder —
  explain → draft → act-with-approval) is designed and awaiting go-ahead.

## Decisions we need

1. **Standard-edition go-to-market timing** — engineering gate is small and known (security hardening
   + packaging verification).
2. **Embedded-intelligence P0 sign-off** — the design is ready; it is the next differentiating bet.
3. **Legacy API sunset policy** — usage is now measured; sunset is a policy call, not engineering.

*Deeper reading: [`../roadmap/STAKEHOLDER_OVERVIEW.md`](../roadmap/STAKEHOLDER_OVERVIEW.md) (strategy) ·
[`../roadmap/PRESENTATION_EXEC.md`](../roadmap/PRESENTATION_EXEC.md) (12-slide deck).*
