# Inspecto — Forward Roadmap

**Status:** current as of 2026-06-19 · **Companion:** [STAKEHOLDER_OVERVIEW.md](STAKEHOLDER_OVERVIEW.md) · **Engineering detail:** [../flow-graph-design.md](../flow-graph-design.md), [../consolidated/05-Roadmap.md](../consolidated/05-Roadmap.md)

> **Timeline convention.** This roadmap sequences work into **Now / Next / Later** horizons and gives **relative effort** sizing (S/M/L). It deliberately does **not** assign calendar dates — the cadence is one minor release per milestone on the active line, and dates are set per planning cycle, not here. Where an item gates revenue or another item, that dependency is called out explicitly.

---

## 1. Strategic themes

| # | Theme | Why it matters | Primary horizon |
|---|---|---|---|
| T1 | **Commercial readiness** | Standard-edition security is the gate to selling into regulated buyers. | Next |
| T2 | **Breadth of ingestion** | Object storage and more native formats widen the addressable feed set. | Next |
| T3 | **Self-service authoring** | Visual flows + AI assist move authoring from expert-only to operator-owned. | Now → Next |
| T4 | **Trust & transparency** | Provenance/lineage + conservation checks as a default operational guarantee. | Now |
| T5 | **Scale-out optionality** | Keep Enterprise distributed seams open without compromising the lean single-node core. | Later |

---

## 2. Horizon: NOW — in mainline, hardening toward the next release

These are built and integrated on the development line; the work remaining is verification, polish, and the release decision.

| ID | Item | Effort | State | Exit criteria |
|---|---|---|---|---|
| N1 | **Flow-graph platform** — authoring, validation, execution as first-class jobs, multi-source merge, incremental flows, materialized views, visual editor | L | Built & tested | A representative `type: flow` job runs end-to-end against seeded data; visual editor verified live; design doc §14 closed |
| N2 | **Data-plane provenance** — per-edge counts, conservation invariant → managed alerts, Sankey overlay | M | Built & tested (off by default) | Provenance verified against a real flow-job run (not just synthetic injection); overlay confirmed against recorded data |
| N3 | **`sink.view` consumer** — REST query of a flow's logical views | S | Shipped in mainline | Done — `/views`, `/views/{name}`, `/views/{name}/data` live with tests |
| N4 | **Edition realignment** — auth-free common core | M | In mainline, uncommitted/ungated | Stakeholder go-ahead to commit/release; confirms the three-edition model is real |

**Now-horizon focus:** finish live end-to-end verification of N1/N2 with real job configs (current verification used synthetic data on a config-less dev backend), then make the release call on N4.

---

## 3. Horizon: NEXT — committed direction, not yet started

Ordered by recommended sequence (see §6 for the rationale).

### 3.1 `inspecto-security` module — Standard edition (T1) · Effort: **L** · **Highest leverage**

The single most important item for commercialization.

- **Scope (in):** an `Authenticator` SPI seam in the core; an OIDC/OAuth2 **resource-server** implementation (validate IAM-issued JWTs — issuer/audience/expiry via JWKS); **RBAC + ABAC** enforcement from token claims/groups; HTTPS (keystore; FIPS-provider option for Gov); actor-attributed, tamper-evident audit; the Angular UI as an OIDC Authorization-Code-+-PKCE public client.
- **Scope (out, by design):** user management, AD/LDAP federation, SAML brokering — these are the **external IAM's** job (Keycloak / WSO2 / Okta / Entra). No identity store in the Java core.
- **Approach:** incremental hardening on the framework-free core — **explicitly not** a Spring/Quarkus migration. At target user counts a framework buys nothing the IAM + small libraries don't, and a lean dependency tree is a compliance asset.
- **Packaging:** delivered as the `inspecto-security` Maven module, assembled into the Standard build via a profile; Personal simply doesn't bundle it.
- **Dependency:** unblocks revenue. Should precede anything that needs per-tenant or per-role gating.
- **Exit criteria:** a Standard build authenticates against a reference IAM, enforces a role matrix, serves over HTTPS, and produces an actor-attributed audit log — with the Personal build unchanged and still auth-free.

### 3.2 Object-storage & network-share connectors (T2) · Effort: **M–L**

- **Scope:** S3 / GCS / Azure Blob / MinIO and NFS/SMB-CIFS connectors on the **existing connector SPI**, in the `inspecto-connectors` module (keeping all new deps out of the core).
- **Leverage:** the embedded analytical engine already reads object storage natively; this is the most-requested ingestion gap; it reuses a proven SPI and the readiness/dedup/watermark machinery.
- **Follow-on:** **etag/version fingerprint dimensions** for richer dedup (depends on these connectors landing).
- **Exit criteria:** a feed collects from each new backend through the standard acquisition path (discover → validate → fetch → dedup) with metrics and gap detection intact.

### 3.3 Unified `parsing:` grammar + JSON/regex frontends (T2) · Effort: **M**

- **Scope:** promote today's frontends under one `parsing:` block (with `csv_settings`/plugin aliases so existing configs keep working), and add two new thin frontends producing rows for the shared backend:
  - **JSON** — wrap native JSON/NDJSON reads; lean on expression-mapping rules for nesting.
  - **text/regex** — read-text + split + named-group regex extraction; covers LDIF and flat XML (nested XML and binary stay on the plugin frontend by design).
- **Exit criteria:** a JSON feed and a regex feed each onboard via `parsing:` with no engine change; all existing delimited/fixed-width/plugin configs continue to pass unchanged.

### 3.4 Flow authoring polish & streaming (T3) · Effort: **M**

- **Scope:** round out the visual flow editor; add a dedicated **run endpoint** for authored flows (today they run via a job config); implement the **adapter stream-consumer runtime** for streaming sources (the land-then-ack seam exists; the consumer loop is the remaining piece).
- **Exit criteria:** an operator can author, validate, run, and observe a flow entirely from the console; a streaming source lands records through the adapter with at-least-once semantics.

### 3.5 Config-authoring completion (T3) · Effort: **S**

- **Scope:** finish the config CRUD-from-body surface (a full listing/`PUT` route) so the assist agent's draft-only skills become one-click apply; jail the database temp directory in the safety validator.
- **Exit criteria:** every assist skill that produces a config can persist it through a validated endpoint.

---

## 4. Horizon: LATER — future / vision (demand-gated)

| ID | Item | Effort | Trigger |
|---|---|---|---|
| L1 | **Enterprise distributed tier** — shared-state backends (Postgres status store, object-store events, shared secrets), distributed scheduler coordination, work distribution, per-tenant ABAC | XL | A deployment whose scale or multi-tenancy actually exceeds the single-node design |
| L2 | **Richer "AI behind every screen" UX** — inline natural-language authoring across the console | L | Parallel track; benefits from GPU availability on the deployment |
| L3 | **Multi-step agent graphs** — provision → watch → roll back orchestration | L | Demand beyond single-shot generate→validate→return skills |
| L4 | **Push/event-notification discovery** — react to source-side notifications instead of polling | M | A source that emits change notifications |
| L5 | **Cross-unit parallelism / Stage-2 streaming** — finer-grained parallelism within a run | M | A workload bottlenecked on per-unit sequencing |

**Guiding rule for Later:** these are deliberately deferred against the single-JVM, crash-isolated ethos. The seams are kept open (stateless engine, pluggable stores, stateless-JWT auth), so none of them require a rewrite when pulled forward — only assembly.

---

## 5. Cross-cutting & continuous

- **UI platform currency** — track the Angular release train; sequence Material/grid deps with each bump.
- **Agent library bump** — adopt the latest reusable agent-kernel when convenient (optional; no behavior change for Inspecto).
- **Living documentation** — keep the operations source-of-truth and these stakeholder docs current with every behavioral change (repository-enforced).
- **Release discipline** — semantic versioning + conventional commits; one mainline; editions assembled per-build; guarded merge-forward (fixes land on the oldest supported line and flow forward; features land on mainline).

---

## 6. Recommended sequence & rationale

```
NOW                         NEXT                                  LATER
────────────────────────────────────────────────────────────────────────────
N1 Flow-graph (verify)   →  3.1 inspecto-security (Standard)  →   L1 Distributed tier
N2 Provenance (verify)      [gates revenue]                       L2 Inline AI UX
N3 Views consumer ✓      →  3.2 Object-storage connectors    →   L3 Agent graphs
N4 Edition realignment      [most-requested ingestion gap]        L4 Push discovery
   (release decision)    →  3.3 Unified parsing / JSON / regex    L5 Finer parallelism
                         →  3.4 Flow authoring polish + streaming
                         →  3.5 Config-authoring completion
```

**Why this order:**

1. **Security first (3.1)** — it is the gating item for commercial deployment. Nothing else converts to revenue until a buyer can deploy securely. It is also self-contained (a new module behind an SPI), so it does not block other tracks.
2. **Object storage second (3.2)** — the highest-demand ingestion gap, lowest technical risk (proven SPI + native engine support), and it compounds the value of the acquisition framework already shipped.
3. **Parsing breadth third (3.3)** — widens the addressable feed set; modest effort; backward-compatible by construction.
4. **Authoring polish fourth (3.4–3.5)** — compounds the value of everything beneath it and is the visible face of self-service, but depends on the platform underneath being solid first.
5. **Distributed tier is demand-gated (L1)** — pulled forward only when a real workload requires it, never speculatively, to protect the lean single-node ethos.

---

## 7. Success measures

| Theme | Measure |
|---|---|
| Commercial readiness | A Standard build deployable against a reference IAM with a working role matrix and HTTPS; Personal build unchanged. |
| Breadth of ingestion | Number of source backends and parsing frontends onboarding with zero core change. |
| Self-service | Share of feeds authored/operated entirely from the console vs. hand-edited config. |
| Trust & transparency | Provenance enabled on production flows; conservation alerts surfaced and triaged as managed objects. |
| Leanness preserved | Core fat-JAR size and core dependency count holding flat as connectors/editions grow. |

---

## 8. What is explicitly *not* on the roadmap

- **Separate edition branches/forks** — editions are build flavors; there is one mainline.
- **A web-framework migration (Spring/Quarkus)** — the framework-free core is a feature (small SBOM, fewer CVEs), not a gap.
- **Fine-tuning / model training** — off-the-shelf instruct models + retrieval + grammar-constrained decoding instead.
- **Distributed-by-default execution** — against the crash-isolated single-JVM ethos; available as the opt-in Enterprise tier only.

---

*For the business framing of these items see [STAKEHOLDER_OVERVIEW.md](STAKEHOLDER_OVERVIEW.md) §10. For engineering-level task detail see [../flow-graph-design.md](../flow-graph-design.md) §14 and [../consolidated/05-Roadmap.md](../consolidated/05-Roadmap.md).*
