# Deployment Topology & Operations Plan — Inspecto

> **Status: DRAFT for stakeholder review — 2026-07-24.** Proposes the deployment offerings (topologies,
> security overlays, scaling/DR posture) and the engineering workstreams (scripts, preflight checks,
> post-deploy verification) needed to sell and operate them. Decision asks in §10 need product/ops sign-off.
>
> Companions: [`EDITIONS.md`](../EDITIONS.md) (edition assembly) · [`BRANCHING.md`](../BRANCHING.md)
> (versions/releases) · [`api/deployment/`](../api/deployment/README.md) (WSO2 + Keycloak blueprints) ·
> [`ops/backup-restore-runbook.md`](../ops/backup-restore-runbook.md) (MNT-5/6) ·
> [`ADVANCED_GUIDE.md`](../ADVANCED_GUIDE.md) (§7 telemetry catalog, §8 persisted state, §9 `-D` flags,
> §11 troubleshooting) · [`superpower/compliance-certifications-plan.md`](compliance-certifications-plan.md)
> (NFR-7 SOC 2 / ISO 27001 / FedRAMP posture) · [`REQUIREMENTS.md`](../REQUIREMENTS.md) (PKG/SEC/OPS/SPC/NFR).
>
> **On approval + ship**: distill as-built topology facts into `okf/backend/build-run/` (new
> `deployment-topologies.md` concept — none exists today) + new `docs/ops/` runbooks, then archive this plan.

**Coverage map** (stakeholder ask → section): topology tiers §2 · TLS/SSL §3.1 · Kerberos §3.3 · KMS/secrets
§3.4 · environments §2.5 · scalability §4 · fault tolerance & DR §5 · per-topology deployment plans §6 ·
scripting requirements §7 · installation system checks §8 · post-deploy validation & verification §9.

---

## 0. Executive summary

Inspecto deploys as **one self-contained bundle per edition** — fat JAR + Angular UI + embedded trimmed JVM
(~90 MB process footprint, NFR-2), zero external dependencies required, air-gap capable (NFR-4). That gives us
a naturally tiered offering, **1:1 with the edition build flavors** (editions are flavors, never branches):

| Tier | Topology | Edition | Client profile |
|---|---|---|---|
| **T1** | Single workstation | Personal | Individual analyst, evaluation, PoC |
| **T2** | Single hardened server | Standard | Team/department; TLS + external IAM (OIDC) |
| **T3** | Gateway-fronted, governed | Enterprise | Multi-team/multi-tenant; WSO2 APIM + Keycloak, ABAC space isolation |
| **T4** | T3 + warm standby (active/passive DR) | Enterprise | Business-critical; site failure tolerance |

**Security is an overlay matrix, not a tier** (§3): TLS (in-process TLS 1.3 or proxy/gateway-terminated),
OIDC against any client IAM, Kerberos/AD SSO **via the IAM tier** (deliberately never in-app), secrets via
resolver references (ENV/SYS/FILE/KEYSTORE shipped; Vault/cloud-KMS deferred = SEC-8), volume-level at-rest
encryption, signed release artifacts (`.sha256` always, GPG `.asc` on `-Sign`). Clients pick overlays to match
their security policy; the compliance mapping (SOC 2 → ISO 27001 → FedRAMP) is the NFR-7 plan's job.

**Honest scaling posture**: the platform is **single-node by design** (NFR-8, an accepted constraint) with a
stateless-JWT control plane. We scale **vertically** with shipped caps (`-Djobs.maxConcurrentRuns`,
`-Dprocessing.duckdb.*`), and we offer **active/passive DR** (T4). Active/active clustering is **not offered
today**; its prerequisites (distributed scheduler coordination, all state on shared backends, work
distribution) are recorded in `EDITIONS.md` as deliberate future seams.

**What must be built to sell this** (§7): most primitives are shipped (packaging + signing, HTTPS, OIDC +
gateway trust, ABAC, backup/verify/restore jobs, Prometheus telemetry, health probes). The gaps are
operational packaging: preflight/install/verify scripts, OS service wrappers (systemd / Windows service),
the `package.ps1 -Edition Enterprise` flavor, a bind-address flag, and upgrade/rollback tooling.

---

## 1. What we deploy — the unit and its state

### 1.1 The bundle (exists today — `inspecto/package.ps1`)

`package.ps1 [-Edition Personal|Standard] [-Sign] [-NoRuntime]` emits `file-processor-deploy.zip`
(+ `file-processor-deploy-linux.zip` when the Linux jmods cache is present), each **always** with a
`.sha256`, optionally GPG-signed (`.asc`, SOC 2 CC8-04):

```
file-processor-deploy/
├─ file-processor.jar            # fat JAR (core)
├─ file-processor-security.jar   # Standard only — OIDC/JWKS resource-server module
├─ ui/                           # built Angular SPA (served via -Dui.dir=./ui)
├─ runtime/                      # jlink-trimmed JVM (omit with -NoRuntime → host JDK 24+ / 25+ with agent modules)
├─ spaces/                       # config tree, pruned of runtime state (keeps _templates, config/, data/samples/)
├─ examples/                     # runnable example suite (PKG-3)
├─ docs/  README.md
└─ run.{sh,bat} · serve.{sh,bat} · ura.{sh,bat}     # generated launchers
```

- **Entry points**: `serve.*` → `com.gamma.control.ControlApi` (long-running control plane + UI — the
  service process); `run.*` → one-shot ETL (`CollectorProcessor`, the JAR's default main class); `ura.*` →
  pre-ETL utility CLI. All launchers carry the **mandatory** `--enable-native-access=ALL-UNNAMED`
  (DuckDB JNI — the JVM crashes on DuckDB init without it).
- `serve.*` reads environment: `PORT`, `SPACES_ROOT`, `CORS_ORIGIN`, `HTTPS_KEYSTORE`/`HTTPS_KEYSTORE_PASSWORD`,
  `AUTH_OIDC_*` — secrets are forwarded as `${ENV:…}` **references**, never literal values. It auto-detects
  `file-processor-security.jar` and flips to `-Dauth.mode=oidc` (Standard).
- **Enterprise flavor gap**: `-Edition` currently validates only `Personal|Standard`. Enterprise is fully
  buildable (`mvn -Pedition-enterprise` = Standard + `inspecto-policy`) but has **no packaging flavor** —
  BACKLOG §6, deliverable SCR-8 in §7.

### 1.2 One process, known ports, known state

- **HTTP(S)**: pure-JDK `HttpServer`/`HttpsServer`, virtual-thread executor. Port `-Dcontrol.port`
  (default **8080**). TLS 1.3 in-process when `-Dhttps.keystore` (PKCS12) + `-Dhttps.keystore.password`
  are set; otherwise plain HTTP. ⚠️ **Binds all interfaces** — there is no bind-address flag today
  (`EDITIONS.md` claims Personal is "localhost only"; the code does not enforce it). Until GAP-1 (§10)
  ships, T1 relies on the OS firewall.
- **Ops endpoints**: `GET /health` (open, liveness `{"status":"UP"}`) · `GET /ready` (readiness + pipeline
  count) · `GET /health/details` (per-subsystem UP/DOWN/NOT_CONFIGURED — auth-gated on Standard) ·
  `GET /metrics` (Prometheus text, **open by design**: scrapers don't carry tokens → restrict by network
  path, §3.6) · `GET /bootstrap` (edition + feature discovery, public even on Standard — the SPA's OIDC
  redirect discovery, and our post-deploy edition probe).
- **Per-space on-disk state** (`spaces/<id>/`): `config/` (authored TOON incl. `flows/` pipelines,
  `roles.toon`, `access-policies.toon` — hot-reloaded by mtime, fail-closed) · `data/` (Dataset partitions,
  Hive-style Parquet via atomic COPY+rename; `data/events/` Parquet event store when enabled) · `audit/`
  (run journal, watermarks, Run Artifacts JSONL) · `duckdb/` (the file-backed stores).
- **Store backend matrix** — every store defaults to in-memory or file and is **opt-in** upgraded per store:

| Store | Default | Durable option (`-D` flags) |
|---|---|---|
| Objects (Incidents/Cases) + links + notes | in-memory | `objects.backend=db` → DuckDB file or `jdbc:postgresql://…` |
| Events + Signals ledger (same store) | in-memory ring | `events.backend=parquet` → rolling Parquet |
| Job-run report · provenance · acquisition ledger | DuckDB files | `*.backend` + `*.db.url` → Postgres |
| Status projection (`DbStatusStore`) | file audit read-through | `status.backend=db` → DuckDB/Postgres |

  ⚠️ Two operational implications: **(a)** the PostgreSQL JDBC driver is **not bundled** in the core fat
  JAR — it must be added to the classpath (decision D2 + SCR-2 launcher `lib/` support); **(b)** every DB
  open **degrades gracefully to in-memory on failure — startup never blocks**. Availability-friendly, but it
  means a mis-pointed store silently loses durability: post-deploy verification MUST assert intended
  backends via `/health/details` (VER-3).

---

## 2. Topology options

### T1 — Personal workstation (evaluation / single analyst)

```
┌─ workstation ─────────────────────────────────────────────┐
│  browser ── http://localhost:8080 ──► serve.{bat,sh}      │
│                                        └─ ControlApi JVM  │
│  state: spaces/<id>/{config,data,audit,duckdb}  (local)   │
│  runtime/: embedded jlink JVM — no host Java needed       │
└────────────────────────────────────────────────────────────┘
```

Personal edition: plain HTTP, **no authentication** (auth-free core; actor attribution via `X-Actor`
fallback). Intended exposure is loopback-only — enforce with the OS firewall until the bind flag (GAP-1)
ships. Backup = the `config_backup` maintenance job or plain zip of `spaces/`. Unzip-and-run installation;
no service wrapper needed.

### T2 — Standard single-node server (team / department)

```
users ──HTTPS──► [ reverse proxy / LB ]          (T2a: proxy terminates TLS)
                     │            — or —
users ──HTTPS(TLS 1.3, -Dhttps.keystore)──┐      (T2b: in-process TLS, no proxy)
                     ▼                    ▼
        ┌─ app host (systemd unit / Windows service — SCR-3) ─────────┐
        │  ControlApi JVM  ──JWT validate (JWKS)──►  [ client IAM ]   │
        │   ├─ spaces/ tree (local SSD, snapshotted)   Keycloak/Okta/ │
        │   └─ optional JDBC stores ──► [ PostgreSQL ] Entra/ADFS:    │
        └───────────────────────────── users, AD/LDAP, SAML, Kerberos ┘
        monitoring ──scrape──► /metrics · /health · /ready
```

Standard edition (`file-processor-security.jar`): AuthN **delegated to the client's IAM** — Inspecto is an
OIDC resource server (Nimbus + JWKS; issuer/audience/expiry). A misconfigured IAM **fails the boot** rather
than serving open — deliberate fail-closed posture. AuthZ = capability RBAC from token claims (`roles.toon`,
per-space overlay, fail-closed). Two TLS variants: **T2a** proxy-terminated (proxy adds gzip for static UI,
HSTS, `/metrics` network restriction) or **T2b** in-process TLS 1.3 for proxy-less estates. Durable stores
on DuckDB files (default) or PostgreSQL. Runs as an OS service (SCR-3 — to build).

### T3 — Enterprise gateway-fronted (governed, multi-team / multi-tenant)

```
browser ──HTTPS/2──► [ WSO2 API Manager ]───────HTTPS──► ┌─ Inspecto (Enterprise) ─────────┐
   │                   TLS · OAuth2 scopes · throttling  │ Bearer JWT re-validated (JWKS)   │
   │                   Backend-JWT: X-JWT-Assertion ───► │ + gateway assertion trust (R0)   │
   └──OIDC Code+PKCE──► [ Keycloak ]                     │ RBAC capabilities + ABAC         │
                          users · roles · AD/LDAP ·      │ PolicyEngine → space isolation   │
                          SAML · Kerberos federation     │ (per-tenant, A4/SPC-5)           │
                                                         ├─ PostgreSQL (all JDBC stores)    │
   audit: access.granted/denied events (A5)              ├─ Parquet event store             │
                                                         └─ spaces/<tenant>/… per tenant    │
```

Enterprise edition (`-Pedition-enterprise` — packaging flavor pending, SCR-8). The gateway is **transport
only** (routing, throttling, CORS, edge OAuth2) — Inspecto re-validates every JWT ("never trust the gateway
blindly"). Multi-tenancy = Spaces + seeded **space-isolation policies** (deny outside the subject's
home-space claim; `canConfigureAccess` exempt; tailorable per space). Every policy deny and route-level
allow is audited as `access.denied`/`access.granted` events. Reference IAM/gateway pair is Keycloak + WSO2
APIM per the shipped blueprints — **illustrative, not yet validated against live instances** (Phase 3, §11);
final vendor split is an open BACKLOG §6 ops decision.

### T4 — Enterprise HA/DR: active/passive warm standby

```
            [ DNS / LB failover ]
              │ (active)              (standby, promote on DR)
              ▼                          ▼
   SITE A ─ Inspecto (T3 stack)   SITE B ─ Inspecto (installed, stopped/idle)
              │                          ▲
              ├─ spaces/ tree ──scheduled sync (robocopy/rsync or storage replication)
              └─ PostgreSQL ────streaming replica──► Postgres standby
   backups: maintenance jobs (zip + SHA-256 manifest) → off-site copy
```

What replicates: the whole `spaces/` tree (config + data + audit + duckdb — after `db_maintenance`
CHECKPOINT for crash-consistent DuckDB files) and Postgres (streaming replication / PITR) where used.
Failover = promote Postgres, start Inspecto on site B from the same bundle version, repoint DNS/LB, run the
§9 verification. **Explicitly not offered: active/active.** NFR-8 records the single-node ceiling as an
accepted constraint; both schedulers are in-JVM. The escape-hatch prerequisites (distributed scheduler
coordination, shared-backend-only state, work distribution) are documented seams — a separate priced
roadmap conversation, not a configuration.

### 2.5 Environments & promotion (applies to every tier)

- **dev → UAT → prod** as separate instances (or separate Spaces on shared lower-tier hardware).
  Config-as-code: authored TOON lives in `spaces/<id>/config/` — version it in the client's git.
- Promotion mechanics that exist today: whole-Space **zip export/import with dry-run** (SPC-2), Space
  Templates (SPC-3), `tools/seed-uat.ps1` (generates a UAT clone space), sample seed scripts
  (`seed-inbox`, `seed-ops`) for demo/UAT data.
- Same bundle artifact promotes through environments (verify `.sha256`/`.asc` at each hop); only env-specific
  `-D` flags / env vars differ. OS matrix: Windows + Linux bundles ship today (D5 commits the support list).

---

## 3. Security overlay matrix

Overlays are **optional and composable per client policy**. Status: ✅ shipped · ⚠️ shipped-with-gap · 🔭 deferred.

| # | Overlay | Mechanism | Status |
|---|---|---|---|
| 3.1 | **TLS in-process** | `-Dhttps.keystore` (PKCS12) + password → JDK `HttpsServer`, TLS 1.3 (SEC-4) | ✅ |
| 3.1 | **TLS at proxy/gateway** | nginx/IIS/ALB or WSO2 terminates; app stays HTTP on a private segment | ✅ pattern |
| 3.2 | **AuthN — OIDC resource server** | `inspecto-security`: Nimbus JWKS validation; BFF session (httpOnly `inspecto_rt` cookie, SameSite=Strict + Origin CSRF) (SEC-3/5) | ✅ Standard+ |
| 3.2 | **Gateway trust** | WSO2 Backend-JWT `X-JWT-Assertion`, second trust anchor (`-Dauth.oidc.gateway.*`); Bearer always wins; unsigned headers never trusted | ✅ Enterprise |
| 3.3 | **Kerberos / AD SSO / SAML / LDAP** | **Delivered at the IAM tier** — Keycloak Kerberos(SPNEGO)/AD federation, SAML brokering; the app only ever validates the resulting OIDC JWT. Deliberate: keeps the framework-free core small (FedRAMP-lean SBOM). Never in-app. | ✅ pattern |
| 3.4 | **Secrets** | `SecretResolver` references `${ENV:}/${SYS:}/${FILE:}/${KEYSTORE:}` (+ `-Dsecrets.keystore.*` PKCS12) — secrets never in TOON, bundles, or argv (SEC-8) | ⚠️ Vault / cloud-KMS provider deferred → D4 |
| 3.4 | **KMS today** | Applies at the disk layer: cloud-KMS-managed volume keys (see 3.5) + client KMS for keystore passphrases via `${ENV:}` injection from their secret manager | ✅ pattern |
| 3.5 | **At-rest encryption** | Volume-level (BitLocker / LUKS / encrypted cloud disks). DuckDB/Parquet files are not app-encrypted | ✅ pattern (app-level 🔭) |
| 3.6 | **Telemetry exposure** | `/health` + `/metrics` are unauthenticated by design → on T2+/T3 restrict them to the monitoring network at the proxy/gateway | ⚠️ needs proxy rule (documented in SCR-4 templates) |
| 3.7 | **AuthZ — RBAC** | Named capabilities from `roles.toon` (hot-reload, fail-closed), Access Profiles, `permissions[]` in the v1 envelope (SEC-7) | ✅ Standard+ |
| 3.7 | **AuthZ — ABAC + tenancy** | `access-policies.toon` deny-overrides PolicyEngine at route + row (`RowScope`) PEPs; seeded space isolation (A4); data-scoped grants (SEC-7d) | ✅ Enterprise |
| 3.8 | **Audit** | Three-layer audit (OPS-3) + policy decision events (A5); actor attribution | ✅ |
| 3.9 | **Release integrity** | `.sha256` always; GPG `.asc` with `-Sign` (CC8-04); lean SBOM (PKG-2); verify-before-unpack is a PRE check | ✅ |
| 3.10 | **Air gap** | No required external services; embedded runtime; hosted-AI SDKs isolated out of core (NFR-4); IAM must be on-prem (Keycloak) | ✅ |
| 3.11 | **Write gate (all editions)** | `-Dassist.write.root` absent → all mutation routes 503 `CONTROL_PLANE_READ_ONLY` (SEC-9) — a legitimate read-only boot mode for the legacy/default space; **boot-time only**, and discovered multi-space roots are always writable | ⚠️ nuance |
| 3.12 | **FIPS (Gov)** | Pattern: JVM FIPS provider under the same `HttpsServer` seam | 🔭 not validated → D7 |
| 3.13 | **Network posture** | ⚠️ GAP-1: server binds all interfaces, no bind flag — mitigate with OS firewall / private subnet until shipped; single-origin CORS `-Dcontrol.cors` | ⚠️ |

Compliance policy mapping (SOC 2 / ISO 27001 / FedRAMP / HIPAA / PCI scoping) is owned by
[`compliance-certifications-plan.md`](compliance-certifications-plan.md); this matrix is its technical inventory.

---

## 4. Scalability & capacity

**Model: scale up, cap explicitly, watch the lag signals.** Stateless JWT auth means no server session to
pin; the ceiling is the single JVM + its disks (NFR-8).

| Knob / signal | What it does | Deployment stance |
|---|---|---|
| `-Djobs.maxConcurrentRuns` | Global semaphore over job runs (default **0 = unbounded**) | **Always set on T2+** (start 2–4) |
| `-Dprocessing.duckdb.memory_limit` / `.threads` / `.temp_directory` / `.max_temp_directory_size` | Caps every DuckDB scratch connection | **Always set on T2+** — unset, each instance assumes ~80% of RAM ⇒ concurrent runs overcommit ⇒ box-wide OOM. On-by-default value is a pending product call (D3) |
| `processing.chunking.max_file_bytes` | Default 0 = a single multi-GB input is **not** auto-chunked | Size RAM for the largest expected file, or set explicitly |
| `inspecto_inbox_oldest_seconds` (+ `InboxStatus` lag) | Ingest backpressure signal | Alert Rule at T2+; capacity review trigger |
| `/metrics` catalog (`ADVANCED_GUIDE` §7) | Prometheus counters/gauges incl. run durations (OPS-4) | Wire into client monitoring from day one |

Known surge gap (**T15**, BACKLOG §3): the Collector admits **all** matching inbox candidates every poll
cycle — `batch.max_files` bounds batch size, not per-cycle admission; the admission cap + hysteresis
controller is deliberately deferred (ingest hot path). Until it ships, the guidance for bursty estates is
upstream drip-feeding + the caps above. **Indicative sizing** (validate in the Phase-2 reference deployment;
`okf/backend/build-run/performance.md` holds the perf notes):

| Tier | App host | Postgres | Notes |
|---|---|---|---|
| T1 | 2 vCPU · 8 GB · SSD 20 GB+ | — | ~90 MB idle footprint (NFR-2) |
| T2 | 4–8 vCPU · 16–32 GB · SSD 100 GB+ | optional, 2 vCPU · 8 GB | `memory_limit` ≈ 25–50% RAM ÷ concurrency |
| T3 | 8–16 vCPU · 32–64 GB · SSD 250 GB+ | 4 vCPU · 16 GB, PITR | + gateway/IAM hosts per vendor sizing |
| T4 | T3 × 2 sites | + streaming replica | + replication bandwidth ≈ daily delta |

**Future — Enterprise horizontal scale (roadmap, not offered today).** For Enterprise estates that outgrow
vertical scaling, the intended future direction is **multi-replica / horizontally-autoscaled pods with load
distribution** (active/active) behind the gateway — the T3 stack replicated `N`-wide instead of the
single-writer node of today. It is the same escape hatch NFR-8 records, gated on the identical prerequisites:
a distributed / leader-elected scheduler (today's in-JVM `ingestLock` and job scheduler are per-process only),
**all** durable state on shared backends (Postgres for every store; the DuckDB/Parquet file tier relocated off
per-node local disk so no replica owns local files), and per-request work distribution. Until those ship, >1
replica against a shared `spaces/` volume corrupts state, so the supported multi-instance posture stays
**active/passive** (T4). A priced roadmap conversation, not a configuration knob — and the natural landing
place for the container work in SCR-11 / D1 once it graduates past "K8s out of scope."

---

## 5. Fault tolerance & disaster recovery

**Restart-safe by design** (NFR-3): crash-isolated, idempotent processing; the ETL commit-ordering invariant
makes a mid-run crash resumable; job non-overlap (`LockingRunner`) prevents double-runs; authored config
re-registers on boot. Recovery from process death is therefore **restart** (the SCR-3 service wrapper's
`Restart=on-failure` is the first FT layer). One deliberate trade-off to monitor: DB-backed stores **degrade
to in-memory rather than failing boot** — pair every durable-store deployment with the VER-3 backend
assertion and a `/health/details` watch.

| Failure | Tier response |
|---|---|
| Process crash | Service wrapper auto-restart; `/ready` gates traffic; in-flight run resumes/re-runs idempotently |
| Disk loss | Restore last verified backup (below); Datasets rebuild from re-ingest where retained |
| Postgres down | Stores degrade to memory (alert!); reconnect = restart after DB recovery |
| Node loss | T2/T3: rebuild from bundle + restore (RTO below). T4: promote standby |
| Site loss | T4 only: DNS/LB failover to site B |

**Backup / verify / restore — already shipped as `maintenance` Job tasks** (no shell scripts; runbook:
[`ops/backup-restore-runbook.md`](../ops/backup-restore-runbook.md)): `backup` (zip + SHA-256 sidecar
manifest, catalogued in the `maintenance_backups` Dataset, `maintenance.backup.completed` signal) →
`backup_verify` chained `on_signal` (mismatch ⇒ CRITICAL `maintenance.backup.verify_failed` — **wire an
Alert Rule + notification channel**, `notify.smtp.*`/`notify.webhook.*`) → `cleanup` retention with
`min_keep` so a sweep can never delete the last backups. DuckDB rule: `db_maintenance` (CHECKPOINT) before
any copy of `duckdb/`. Restore is fail-closed (manifest required, hash-verified, path-jailed, dry-run
preview, conflict-blocking) and supports **restore-into-a-new-space** — which doubles as our DR-drill and
environment-clone mechanic. What the platform does NOT do itself: move backups **off the box** — the off-site
copy step is SCR-5.

**Proposed recovery targets** (for sign-off, D6 — they become contract SLOs):

| Tier | Backup cadence | RPO | RTO | DR drill |
|---|---|---|---|---|
| T1 | daily `config_backup` (04:00 sample job) | ≤ 24 h | ≤ 4 h (reinstall + restore) | — |
| T2 | hourly config + daily full (post-CHECKPOINT) + volume snapshot | ≤ 1 h | ≤ 1 h | yearly restore test |
| T3 | T2 + Postgres PITR/WAL archiving + off-site copy | ≤ 15 min | ≤ 2 h (rebuild) | half-yearly restore drill |
| T4 | T3 + spaces-tree sync + streaming replica | ≤ 5–15 min (async) | ≤ 30 min (promote) | quarterly failover drill |

---

## 6. Deployment plans (per topology)

Every plan is the same five beats — **preflight (§8) → install → configure → start → verify (§9)** — with
tier-specific content. Launch **from the bundle root**: pipeline-internal paths (`schema_file`, `dirs.*`)
resolve against the JVM working directory, not the space root (PROJECT_NOTES gotcha).

- **T1**: verify `.sha256` → unzip → `serve.bat`/`serve.sh` → §9 basic block. Uninstall = delete the folder.
- **T2**: preflight → unzip to `/opt/inspecto` (or `C:\inspecto`) → drop `postgresql.jar` into `lib/` if
  DB-backed (D2/SCR-2) → set env (`PORT`, `HTTPS_KEYSTORE*` or proxy config, `AUTH_OIDC_*` from the client
  IAM, store backends, **both cap flags**) → install service (SCR-3) → start → §9 standard block (incl. the
  401-unauthenticated probe and backend assertion) → register backup jobs + Alert Rule → volume snapshot
  schedule.
- **T3**: T2 sequence, plus: import the API definition into WSO2 (`apictl`, from
  [`api/deployment/`](../api/deployment/README.md)) and the Keycloak realm blueprint → set
  `-Dauth.oidc.gateway.*` trust anchors → author tenant Spaces + map the `space` claim (`roles.toon`
  `identity:` allowlist) → confirm seeded isolation policies (or tailor per space) → §9 enterprise block
  (deny-path probes + decision-audit evidence) → UAT space via export/import dry-run before prod cutover.
- **T4**: T3 on both sites (same bundle version, verified checksums) → Postgres streaming replica →
  scheduled `spaces/` sync (post-CHECKPOINT) → documented promote runbook (stop A if alive → promote
  Postgres → start B → repoint LB/DNS → §9 full block) → quarterly drill.

**Upgrade & rollback (all tiers)**: SemVer bundles; one version spans all editions (classifier differs);
fixes arrive via merge-forward per [`BRANCHING.md`](../BRANCHING.md). Procedure: pre-upgrade `config_backup`
→ keep the N-1 bundle beside the new one → stop service → swap bundle (state lives in `spaces/` + DB, outside
the bundle) → start → §9 verify. Rollback = start N-1 bundle + restore the pre-upgrade backup if config
migrated. Legacy-API sunset (`-Dapi.legacy.routes=off`) follows the soak-gated
[`ops/legacy-api-sunset-runbook.md`](../ops/legacy-api-sunset-runbook.md), never a flag flipped during upgrade.

---

## 7. Scripting requirements — build inventory

Exists today: `package.ps1` (build/bundle/sign) · generated `run/serve/ura` launchers · maintenance-job
backup stack · smoke harness (`.claude/skills/smoke`) · seed/UAT tooling. To build (acceptance criteria in
brackets; all cross-platform `ps1` + `sh` unless noted):

| ID | Script / asset | Tier | Content [acceptance] |
|---|---|---|---|
| SCR-1 | `preflight` | all | §8 checks, JSON report + non-zero exit on hard-fail [runs offline; catches every §8 row] |
| SCR-2 | Launcher `lib/` support | T2+ | `serve.*` adds `lib/*.jar` to classpath (Postgres driver et al.) [DB-backed boot with driver dropped in, no script edits] |
| SCR-3 | Service wrappers | T2+ | systemd unit (`Restart=on-failure`, `WorkingDirectory=` bundle root, `EnvironmentFile=`) + Windows service (WinSW or `sc.exe` wrapper — vendor decision) [survives reboot + kill -9] |
| SCR-4 | Proxy/TLS templates | T2a/T3 | nginx/IIS reference configs: TLS, HSTS, static-UI gzip, `/metrics`+`/health/details` network restriction [checklist-verifiable] |
| SCR-5 | `backup-offsite` | T2+ | Post-`backup_verify` copy of verified archives off-box (share/S3-compatible) [restore succeeds from off-site copy alone] |
| SCR-6 | `verify` | all | §9 as a script — productized smoke: probes + evidence table + exit code [green on reference deploys; red on each seeded fault] |
| SCR-7 | `upgrade` / `rollback` | T2+ | §6 procedure automated incl. N-1 retention [drill both directions on the reference deploy] |
| SCR-8 | `package.ps1 -Edition Enterprise` | T3+ | Add ValidateSet value + `inspecto-policy` bundling [BACKLOG §6; currently blocked by another session's uncommitted edit to the file] |
| SCR-9 | Launcher hygiene | all | Remove dead `CONTROL_TOKEN`/`ASSIST_TOKEN` lines from `serve.*`/`package.ps1` (token auth was removed 2026-06-16 — no Java code reads them) [grep-clean] |
| SCR-10 | Bundle-docs ACL fix | — | 13 files under `docs/archived-documents/plans-archive/` carry a broken deny-ACL and are silently skipped from every bundle; needs Administrator `takeown`+`icacls` [bundle diff shows them back] |
| SCR-11 | Container image (optional) | per D1 | Dockerfile + compose reference (app + Postgres); K8s explicitly out of scope until D1 says otherwise |

---

## 8. Installation system checks (preflight)

Machine-readable (`preflight … --json`), each row `PASS/WARN/FAIL`; any FAIL blocks install.

| Check | Detail |
|---|---|
| Artifact integrity | `.sha256` match (always); `.asc` GPG signature when policy requires (3.9) |
| OS / arch | Matches bundle variant (Windows vs `-linux`); glibc for DuckDB JNI on Linux |
| Runtime | `runtime/` present, or host JDK ≥ 24 (≥ 25 if agent/intelligence modules deployed) with `--enable-native-access` support |
| Native access | Launcher smoke: DuckDB opens in-process (catches a stripped `--enable-native-access=ALL-UNNAMED`) |
| Resources | CPU/RAM/disk ≥ tier row in §4; temp dir capacity vs `max_temp_directory_size` |
| Port | `control.port` (default 8080) free |
| Filesystem | Write permission on bundle root + `spaces/` tree; bundle path == intended CWD (path-resolution gotcha) |
| Clock | NTP-synced (JWT `exp`/`nbf` tolerance is only 60 s — skew breaks Standard/Enterprise auth) |
| OS limits (Linux) | open-files ulimit sane for Parquet partition fan-out |
| Network posture | Firewall rule present given bind-all (GAP-1); CORS origin decided |
| Postgres (if used) | Reachable; driver jar in `lib/`; credentials via `${ENV:…}` resolve |
| IAM (T2+) | Issuer + JWKS URI reachable **before** first boot (misconfig = deliberate boot failure); keystore valid + not near expiry (T2b) |
| Gateway (T3) | Gateway JWKS reachable; `X-JWT-Assertion` header agreed |
| Model host (optional) | Local model endpoint reachable (embedded intelligence is local-models-only, QA-only today) |

---

## 9. Post-deployment validation & verification

Basis: the existing smoke harness + e2e suite, productized as SCR-6. Evidence table (endpoint → status →
body extract) attached to the acceptance sign-off. **Basic block (every tier)**:

- **VER-1 liveness/readiness**: `/health` = 200 `{"status":"UP"}`; `/ready` reports the expected pipeline count.
- **VER-2 edition probe**: `/bootstrap` `edition` matches intent (`personal` vs `standard`) — catches a
  missing security jar or `auth.mode` immediately.
- **VER-3 backend assertion**: `/health/details` shows every intended subsystem `UP` (not silently
  `NOT_CONFIGURED`/in-memory fallback) — the graceful-degradation counter-check.
- **VER-4 telemetry**: `/metrics` scrapes and parses (Prometheus text 0.0.4).
- **VER-5 functional round-trip**: seed the inbox (`seed-inbox`) → poll → Dataset partitions written →
  run + events visible; UI loads with client-side route fallback.

**Standard block (T2+)** adds: **VER-6 auth**: unauthenticated API call → 401; valid IAM token → 200 with
`permissions[]`; expired token rejected. **VER-7 TLS**: handshake pins TLS 1.3 (T2b) or proxy chain + HSTS
(T2a); `/metrics` unreachable from a non-monitoring address. **VER-8 durability**: incident round-trip via
`seed-ops` with `objects.backend=db`, still present after service restart. **VER-9 backup chain**: trigger
`config_backup` (`dryRun=true` then real) → `backup_verify` green → restore dry-run into a scratch space.

**Enterprise block (T3/T4)** adds: **VER-10 gateway path**: request via WSO2 with Backend-JWT only →
accepted; tampered/unsigned assertion → rejected. **VER-11 isolation**: tenant-A subject reading tenant-B
space → deny + `access.denied` event present (decision-audit evidence). **VER-12 (T4) failover drill**:
promote standby per runbook inside the RTO target; then fail back.

Sign-off = every applicable VER row green, evidence archived with the deployment record (Run Artifacts +
the `maintenance_backups` catalog give the durable trail).

---

## 10. Gaps, risks & decision asks

Design/reality gaps (tracked honestly — none block T1/T2 sales today):

- **GAP-1 bind-all**: no bind-address flag; `EDITIONS.md`'s "localhost only" claim is aspirational. Small
  core change (`-Dcontrol.bind`) + doc fix; firewall guidance interim. Highest-priority hardening item.
- **GAP-2 Enterprise packaging** (SCR-8) · **GAP-3 no service wrappers/installers** (SCR-3) · **GAP-4
  DuckDB cap not on-by-default** (D3; mitigated by mandatory-flag guidance) · **GAP-5 T15 surge admission**
  (deferred hot-path work) · **GAP-6 Vault/KMS provider** (SEC-8 deferred; D4) · **GAP-7 gateway/IAM
  blueprints unvalidated live** (Phase 3) · **GAP-8 Postgres driver not bundled** (D2) · **GAP-9 launcher
  token-line debris** (SCR-9) · **GAP-10 bundle silently missing 13 archived docs** (SCR-10).

| # | Decision ask | Options / recommendation |
|---|---|---|
| D1 | Offer a container image? | Recommend: yes as T2 convenience (SCR-11), K8s out of scope |
| D2 | Bundle the Postgres JDBC driver in Standard? | Recommend: yes (license-compatible BSD) + `lib/` mechanism either way |
| D3 | On-by-default DuckDB memory cap value | Needs product call (BACKLOG §5 "no new defaults" stance) |
| D4 | Vault/cloud-KMS `SecretsProvider` priority | Gate on first client security policy that requires it |
| D5 | Committed OS support matrix | Propose: Windows Server 2019+, RHEL/Ubuntu LTS x64 |
| D6 | RPO/RTO targets (§5) as contract SLOs | Sign off or adjust before first T3/T4 proposal |
| D7 | Gov/FIPS variant on the roadmap? | Only with a concrete opportunity; pattern documented |
| D8 | IAM/gateway reference pair | Keycloak + WSO2 APIM (as blueprinted) vs WSO2 IS — ops/evidence call (BACKLOG §6) |

## 11. Sequencing (proposed workstreams)

- **Phase 0 — hygiene & unblockers** (small, immediate): GAP-1 bind flag, SCR-9, SCR-10, SCR-8 (once the
  file frees up) → makes all three editions packagable and the docs honest.
- **Phase 1 — script suite**: SCR-1..6 → T1/T2 installable and verifiable by a client operator, not just us.
- **Phase 2 — T2 reference deployment**: one real server, both TLS variants, Postgres-backed, monitored;
  validates §4 sizing + §5 RPO/RTO; SCR-7 drilled. Also closes OPS-5's "needs a live deploy" verification.
- **Phase 3 — T3 reference**: live Keycloak + WSO2 pair (resolves D8/GAP-7), tenant-isolation VER evidence pack.
- **Phase 4 — T4 DR pack**: standby runbook + quarterly-drill template; publish SLOs per D6.
- **Phase 5 (optional, per D1)** — container image.

Each phase ends with its §9 evidence pack; on full ship, this plan distills into
`okf/backend/build-run/deployment-topologies.md` + `docs/ops/` runbooks and archives per the doc lifecycle.
