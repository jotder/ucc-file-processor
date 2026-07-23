# NFR-7 — Compliance certifications plan (SOC 2 Type I/II → ISO 27001 → FedRAMP alignment; HIPAA/PCI scoped)

**Status:** DRAFT 2026-07-23 — sequencing decision pending (§6 Q1). Expanded same day to
control-level coverage for SOC 2 / ISO 27001 / FedRAMP (§2b, C6). · **Owner:** enterprise-PM track
(org-side controls are Gamma Analytics', not this repo's — §5 boundary). · **Companions:**
`../REQUIREMENTS.md` NFR-7 (PARTIAL: posture shipped, certifications not started) ·
`rbac-abac-plan.md` (CC6 access-control dependency) · `../EDITIONS.md`.

## 1. What NFR-7 actually requires

Certifications certify **the organization operating the product**, not the codebase alone. This plan
covers the *product-side* controls + evidence (what this repo can ship); org-side items are named in
§5 so they're visible, but not planned here. Recommended sequence (Q1 to confirm):

1. **SOC 2 Type I** first — fastest to attain, the one prospects ask for, and ~90% of its technical
   controls are already shipped product posture.
2. **SOC 2 Type II** — same controls + a 3–12-month observation window; starts automatically once
   Type I controls operate.
3. **ISO 27001** — heavy control overlap with SOC 2 (one control matrix, two mappings); org-ISMS
   dominated. Product-side work = the Annex A technological controls (§2b-2).
4. **FedRAMP — 800-53 control *alignment* + ATO-support package (C6), not authorization.**
   FedRAMP authorizes *cloud services*; Inspecto is self-hosted software, so the honest posture is
   "FedRAMP-ready / supports your ATO": NIST 800-53 rev 5 control implementation statements for
   the controls the product implements or inherits (§2b-3), a hardening guide, FIPS-mode crypto
   option. Actual authorization only enters scope if a hosted SaaS offering ever exists (§6 Q5).
5. **HIPAA / PCI — scoping statements only, demand-gated.** One-page applicability statement each
   (self-hosted: no PHI/PAN leaves the customer's deployment; Inspecto is in the data path only
   where the customer routes it), NOT certification work.

## 2. Control inventory — what's already shipped (map, don't rebuild)

The pitch: Inspecto's architecture *is* the control set. C2 turns these into an auditor-readable
matrix (SOC 2 trust-services criteria ↔ ISO 27001 Annex A ↔ product feature):

| Shipped posture | Requirement row | SOC 2 criteria (indicative) |
|---|---|---|
| Immutable who-did-what **Audit Log** (3-layer: file/batch, provenance, audit) | OPS-3 | CC4/CC7 monitoring |
| OIDC resource-server auth, BFF session, no-browser refresh token, TLS | SEC-5/6, W6 | CC6 logical access |
| Data-scoped grants (SEC-7d caseType), X-Actor spoof rejection | SEC-7 | CC6 |
| Secrets: env/file/JCEKS keystore, no plaintext in config | SEC-8 | CC6.1 |
| Write-root fail-closed gate (503), ConfigSafetyValidator, path jail | SEC-9 | CC8 change control |
| **Lean SBOM by design** — framework-free core, SDK-free connectors, network deps isolated | PKG-2, NFR-4 | CC9 / supply chain |
| Air-gap operation (no egress, offline basemap/AI absent) | NFR-4 | CC6.7 |
| Quality gates: GAUNTLET, axe CI, token lint, branch-policy CI, Conventional Commits | NFR-9 | CC8 SDLC |

**Known control gaps** (product work, C4): SBOM as a *generated artifact* (the leanness is real but
unattested) · log/audit retention configuration + documented backup/restore · vulnerability-
management evidence (deps are few by design, but there's no scan/attestation step) · access-review
support (needs `rbac-abac-plan` R5 effective-grants view) · full RBAC enforcement (CC6 wants
role-based access — currently capability gates exist, role enforcement is the R-workstreams).

## 2b. Framework coverage — control-level detail

### 2b-1 · SOC 2 Type I & Type II (AICPA Trust Services Criteria)

**Type I** attests control *design* at a point in time; **Type II** attests *operating
effectiveness* over a window (target 6 months; 3 is the floor auditors accept). Same controls —
Type II adds the burden that every control must leave **time-stamped evidence continuously**,
which is why C3 (evidence automation) is sequenced before the observation window opens.

Scope: the **Security** (common criteria) category mandatory; add **Availability** and
**Processing Integrity** (Inspecto's pitch — conservation invariants, batch-atomic commits,
quarantine — *is* processing integrity); defer Confidentiality/Privacy to demand.

| TSC | What it wants | Product coverage | Gap → workstream |
|---|---|---|---|
| CC1–CC3 (governance, risk) | Org structures, risk assessment | — org-side (§5) | — |
| CC4 (monitoring) | Ongoing control monitoring | Signals ledger, Metrics, Alert Rules, Audit Log | evidence runbook (C3) |
| CC5 (control activities) | Controls tied to objectives | controls-matrix itself (C2) | C2 |
| CC6 (logical access) | AuthN/AuthZ, least privilege, access reviews | OIDC + BFF session, capability gates, SEC-7d data scopes; **full RBAC = rbac-abac-plan R1–R5** | R-workstreams land before the Type II window; access-review view (R5) |
| CC7 (system ops) | Anomaly detection, incident mgmt | Alert Rules, Incidents/Case Manager, notification feed | incident-response policy doc (C5) |
| CC8 (change mgmt) | Authorized, tested, tracked changes | Conventional Commits + branch-policy CI + GAUNTLET + all-editions builds; release checksums/signing (C3) | document the SDLC as a control narrative (C2) |
| CC9 (risk mitigation / vendors) | Vendor + business-disruption risk | lean SBOM, air-gap, no runtime SaaS deps | SBOM artifact (C3); backup/restore runbook (C4) |
| A1 (Availability) | Capacity, recovery | single-node by design (NFR-8), crash-isolated idempotent Runs | documented RTO/RPO + restore drill (C4) |
| PI1 (Processing Integrity) | Complete, accurate, timely processing | Expectations, quarantine semantics, provenance + conservation invariant (OPS-5), ContentHash parity | OPS-5 live verification (already BACKLOG §2) |

### 2b-2 · ISO 27001:2022 (Annex A — 93 controls, 4 themes)

Organizational (5.x), People (6.x), Physical (7.x) are org-/deployment-side (§5; physical is
inherited from the customer's site in self-hosted deployments — state it, don't own it). The
product-side theme is **Technological (8.x)**:

| Annex A (8.x) | Product coverage / gap |
|---|---|
| 8.2–8.5 privileged access, restriction, secure auth | OIDC + Roles/Capabilities (R-workstreams); privileged = Admin role + `canConfigureAccess` |
| 8.8 vulnerability management | **gap** → C4 offline dep-review step + advisory-watch process (few deps by design helps) |
| 8.9 configuration management | TOON config + ConfigSafetyValidator + write-root gate; hardening guide (C6) doubles here |
| 8.12 data-leakage prevention | air-gap/no-egress posture (NFR-4) — a genuine differentiator, write it up |
| 8.13 backup | **gap** → C4 backup/restore runbook + restore drill evidence |
| 8.15–8.17 logging, monitoring, clock sync | Audit Log + Signals + Metrics; clock-sync = deployment note in hardening guide |
| 8.24 cryptography | TLS, JCEKS secrets, release signing; crypto policy doc (C5); FIPS option (C6) |
| 8.25–8.31 secure SDLC, testing, environments | NFR-9 gates + branch policy + editions CI — mostly narrative work (C2) |
| 8.32 change management | same CC8 evidence as SOC 2 — one narrative, two mappings |

**Statement of Applicability (SoA)** — the ISO deliverable enumerating all 93 controls with
applicable/excluded + justification — is generated *from* the C2 matrix (add an SoA export column
rather than writing a second document).

### 2b-3 · FedRAMP (NIST 800-53 rev 5, Moderate baseline as the working target)

Deliverable = **C6: control implementation statements** (SSP-ready language an agency can lift
into their ATO package) covering the families the product implements, plus explicit
inheritance/customer-responsibility statements for the rest — the standard "customer
responsibility matrix" shape:

| Family | Posture |
|---|---|
| AC (access control) | product-implemented: RBAC/ABAC (R+A workstreams), session mgmt, least privilege; AC-2 account lifecycle = *inherited from customer IdP* (document the claim contract) |
| AU (audit) | product-implemented: Audit Log, signals; **gaps**: AU-4/AU-11 retention config (C4), AU-9 audit-record protection statement (append-only guarantees — verify + document what the store actually guarantees, don't overclaim) |
| IA (identification & authN) | delegated to customer IdP via OIDC — IA-2 MFA etc. are *inherited*; product statement = "enforces authenticated subjects when an Authenticator is present" |
| SC (system & comms protection) | TLS in transit; **gap**: SC-13 *FIPS-validated* crypto — add a documented FIPS mode (run on a FIPS-enabled JVM/provider; verify Nimbus/JCEKS paths under it) (C6) |
| CM (config mgmt) | ConfigSafetyValidator, write-root gate, TOON-as-config-baseline; hardening guide (C6) = CM-6 baseline |
| SI (system integrity) | Expectations/quarantine (SI-10 input validation is literally the product), SI-2 flaw remediation = release/backport process (BRANCHING merge-forward is the evidence) |
| RA (risk assessment) | RA-5 vuln scanning: offline constraint → pinned-deps diff + SBOM-against-advisory check (C4); org owns the risk-assessment process |
| CA / CP / IR / PE / PS / SA-9 etc. | org- or customer-side; named in the responsibility matrix, not product work |

**Not undertaken:** 3PAO assessment, ConMon program, POA&M operation — those exist only if a
hosted offering makes Inspecto a CSP (§6 Q5).

## 3. Workstreams

- **C1 — scoping decisions + framework applicability statements.** Confirm §1 sequence with
  product; write the four one-page applicability statements (SOC 2 in-scope services; ISO 27001
  ISMS boundary; FedRAMP/HIPAA/PCI applicability given self-hosted deployment). →
  `compliance/scope/`.
- **C2 — control matrix.** One table: control id → SOC 2 TSC ↔ ISO 27001 Annex A ↔ NIST 800-53
  → implementing product feature (file/route/gate) → evidence source (test, CI run, audit-log
  query, doc) → responsibility (product / org / customer / IdP-inherited). Seeded from §2 + §2b;
  the ISO **SoA** and the FedRAMP **customer-responsibility matrix** are *exports of this table*,
  never separate documents. Living doc, updated as rbac-abac-plan workstreams land. →
  `compliance/controls-matrix.md`.
- **C3 — evidence automation (product work).**
  - **Release integrity (CC8):** SHA-256 checksum + optional GPG detached signature per deploy
    artifact (`package.ps1` step + customer verification runbook in `compliance/`), so releases
    are verifiable for integrity AND authenticity.
  - **SBOM artifact:** emit CycloneDX JSON at package time from the offline Maven reactor's
    resolved dependency list (hand-rolled step in `package.ps1`/a small Maven exec — no new online
    plugin; the dep list is tiny by design, which is the NFR-7 selling point). Ship it inside the
    deploy bundle next to the .sha256/.asc.
  - **Audit-log export:** documented, repeatable auditor extraction (existing routes + a
    `compliance/evidence/` runbook; add a retention statement — see C4).
  - **CI evidence:** branch-policy + all-editions build + GAUNTLET runs are the CC8 evidence —
    document where an auditor finds them.
- **C4 — close the product control gaps** (each its own small change, normal release flow):
  retention config for audit/notification/signal stores (partially exists: `notification_prune`,
  `ledger_prune` — document + fill gaps) · backup/restore runbook for the write root + state
  stores · dependency-review step (offline: a pinned-versions diff check in CI, not a scanner
  SaaS) · the RBAC dependencies stay in `rbac-abac-plan` (R1/R2/R5) — this plan only *consumes*
  them; do not partially implement access control here (BACKLOG §6 rule).
- **C5 — policy pack.** The written security policies auditors require (access control, crypto,
  change management, incident response, retention) — templates live in `compliance/policies/`;
  content is org-owned (§5) but versioned here so every deployment ships with its policy set.
- **C6 — FedRAMP ATO-support package (§2b-3).** 800-53 control implementation statements
  (SSP-ready) + the customer-responsibility matrix (C2 export) + a **hardening guide** (TLS
  config, IdP claims contract, write-root/permissions, clock sync, CM-6 baseline) + a documented
  **FIPS mode** (run + verify the Nimbus/JCEKS/TLS paths on a FIPS-enabled JVM provider — a test
  matrix leg, not new crypto code). → `compliance/fedramp/`. Demand-gated start; sequenced after
  C2 exists to export from.

## 4. Repo layout

```
compliance/
  scope/                      # C1 applicability statements (soc2, iso27001, fedramp, hipaa, pci)
  controls-matrix.md          # C2 — the single mapping table
  policies/                   # C5 — numbered policy docs (access, crypto, change mgmt, …)
  evidence/                   # C3 runbooks (release verification, audit-log export, CI pointers)
  fedramp/                    # C6 — 800-53 implementation statements, responsibility matrix,
                              #      hardening guide, FIPS-mode notes
```

Tracked in git (it's product documentation, not secrets); shipped in the deploy bundle's docs.

## 5. Boundary — org-side (named, NOT planned here)

Auditor selection + engagement, the ISMS itself, HR/vendor/asset management policies in force,
penetration test engagement, risk assessments, the Type II observation window, BAA legal templates.
None of these are repo work; the repo's job is to make the product-side answer "yes, and here's
the evidence" for every technical control an auditor asks about.

## 6. Open questions

1. **Sequence sign-off (product):** SOC 2 Type I → II → ISO 27001, with FedRAMP/HIPAA/PCI as
   scoping statements only — confirm, or reorder against actual prospect demand.
2. **SBOM format:** CycloneDX JSON (recommended; widest tool acceptance) vs SPDX.
3. **Where does the GPG release key live** (org secret management — blocks C3 release signing
   being *routine* rather than merely possible).
4. Does any near-term prospect actually need HIPAA/PCI language beyond the applicability
   statement? (Drives whether C1's one-pagers grow controls.)
5. **Is a hosted SaaS offering ever planned?** If yes, FedRAMP shifts from C6 alignment to real
   authorization (3PAO, ConMon, POA&M — a program, not a workstream) and SOC 2 scope grows the
   Availability category's infra controls. Today's answer assumed: self-hosted only.
6. **SOC 2 Type II observation window length** (3 vs 6 months) and when to open it — gated on the
   rbac-abac-plan R-workstreams landing, since CC6 controls must operate during the window.
7. **FedRAMP baseline target** for C6 statements: Moderate (assumed — typical for agency data
   tools) vs Low.
