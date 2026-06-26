# Acquire — Controller Service + Processor Design

> **Status (2026-06-24): DESIGN / proposal — not yet built.** A deepening of
> [`flow-graph-design.md`](flow-graph-design.md) for the **acquire layer**, framed in NiFi's two core
> abstractions: **Controller Service** (a reusable, testable connection) and **Processor** (a data-source
> acquisition step). It does **not** introduce a new runtime — same locked decision as the flow graph:
> NiFi *authoring + per-component testability* on Inspecto's **batch-atomic engine**, no inter-node queues.
> This doc settles three things flow-graph-design.md left thin for acquire: (1) the **connection probe**
> (permission + list, not just reachability), (2) the **List + Fetch** decomposition of the `acquisition`
> node, (3) how **per-data-source scheduling** falls out of the existing flow/trigger model.
>
> **Confirmed by review (2026-06-24):** NiFi *model* on the batch runtime; List + Fetch split; connectors
> *repo* split **deferred** (today `inspecto-connectors` is already an optional module with zero core
> forward-deps, so S3/GCS/Kerberos heavy deps stay out of the lean fat-JAR regardless).

## 1. The mapping (NiFi → Inspecto)

| NiFi | Means | Inspecto today | Gap this doc fills |
|---|---|---|---|
| **Controller Service** | reusable shared resource (DBCPConnectionPool, SSLContextService, SSH context); configured once, **validated/enabled**, referenced by many processors, shows "referencing components" | [`ConnectionProfile`](../inspecto/src/main/java/com/gamma/acquire/ConnectionProfile.java) + [`ConnectionRegistry`](../inspecto/src/main/java/com/gamma/acquire/ConnectionRegistry.java) + [`SecretResolver`](../inspecto/src/main/java/com/gamma/acquire/SecretResolver.java); referenced via `use: connections/<id>` (flow-graph-design.md §5.2) | a real **probe** (§3) + a light validate/enable + "referencing flows" |
| **Processor** | unit of work (ListSFTP, FetchSFTP, GetFile, ExecuteSQL); references a controller service, own scheduling, run-once/test, emits flowfiles | `acquisition` node (flow-graph-design.md §3.1) backed by `source:` + [`SourceConnector`](../inspecto/src/main/java/com/gamma/acquire/SourceConnector.java); [`SourceProcessor`](../inspecto/src/main/java/com/gamma/inspector/SourceProcessor.java) poll cycle | **List + Fetch** split (§4); per-source schedule (§5) |
| **FlowFile** | content + attributes, queued | a batch / record-set (flow-graph-design.md locked decision 1) | — unchanged: no queues |
| **"Test this processor"** | run it alone given upstream | `preview(sample)→result` + `/flows/{id}/dry-run` (flow-graph-design.md §7.2) | reuse as-is for List/Fetch (§6) |

The headline: **Connection = Controller Service** and **Data Source = acquisition Processor** are already
the repo's "referenced component" + "acquisition node". This doc does not add a parallel concept — it
sharpens these two and fills the probe/split/schedule gaps.

## 1A. The two contracts, taken literally

NiFi's abstractions are more specific than "reusable connection" / "unit of work"; the specifics drive the
design. We adopt the **contracts**, not the queued runtime (see §10 for the one deliberate non-adoption).

**Controller Service** — a lifecycle-managed, *validated*, *reference-typed* resource:
- States `DISABLED → ENABLING → ENABLED` + `VALID/INVALID` from validation. (Inspecto keeps this **light**:
  no per-service daemon; *validate* = config + secret-refs resolve, *test* = the §3 probe, "enabled" =
  referenced + valid. The **states are modelled** even though enabling is just validate+probe.)
- A processor references it through a **typed property** ("identifies a controller service of type X").
- **Gating rule (real UX, not a label):** a processor is **INVALID and cannot run/test** while a service it
  references is invalid/disabled — e.g. a Fetch can't run until its Connection tests green (§6).
- Shows its **referencing components**.

**Processor** — likewise a precise contract:
- **Typed property descriptors** (required / sensitive / allowable-values / *identifies-a-controller-service*).
- **Relationships** = named outputs (`success`/`failure`/`unmatched`/`gap`) — already Inspecto flow edges.
- **`@InputRequirement`**: `INPUT_FORBIDDEN` = a source (`acquisition.list`); `INPUT_REQUIRED` = consumes
  upstream (`acquisition.fetch`). This is how the engine knows List is schedulable and Fetch is data-driven.
- Independent **scheduling** (timer/cron/event = `FlowTrigger`) and **validate / start / stop / test**.
- **State**: a List processor is *stateful* (remembers what it already listed) — see §4.

## 2. Controller Service = Connection (lifecycle)

`ConnectionProfile` already *is* a controller service: authored as `*_connection.toon`, loaded into a by-id
registry, secrets held as `${ENV:..}` references (never stored — masked in `toMap()`), reused by many flows
via `use: connections/<id>` (the dedup in flow-graph-design.md §5.2).

Additions, all light and additive:
- **Validate → enable → test** lifecycle. NiFi disables a controller service before edit and enables to
  validate. For Inspecto (file truth, no daemon per service) this is: *validate* = config + secret-refs
  resolve (already in `ConnectionTester.secretsResolve`); *test* = the §3 probe; "enabled" is implicit (a
  referenced, valid profile). We expose **validate** and **test** results; we do **not** add a stateful
  enable/disable daemon in v1 (overkill for the batch model).
- **Referencing components** — "which flows/data-sources use this connection" — generalises the existing
  `connectionInUse` 409 guard (flow-graph-design.md §5.4) into a read view for the UI.

### 2.1 Security/credentials are their *own* controller services (the NiFi decomposition)

The most important consequence of thinking in NiFi terms: NiFi does **not** inline Kerberos/SSL/SSH onto a
connection. It has separate `StandardSSLContextService`, `KerberosCredentialsService`,
`AWSCredentialsProviderControllerService`, `GCPCredentialsControllerService`, … and a *connection*
**references** them. That is exactly why a truststore or a Kerberos principal is configured **once** and
shared across many connections — which is the whole point of the user's "connectors will grow (S3/GCS) +
Kerberos" goal: each new auth mechanism is a credentials controller service, not a new field on every
connection.

The user's own reference screenshots already embody this: DBeaver's **Network Profiles** (SSH Tunnel /
Kubernetes / AWS SSM / Proxy), **Authentication profiles**, and **Truststore configuration** are *separate,
reusable, named resources* a connection points at — i.e. controller services.

So the target decomposition (vs. today's single `ConnectionProfile` with an inline `tunnel` field):

```
connection (controller service)   ──references──▶  network/tunnel profile (SSH tunnel / proxy)
                                   ──references──▶  credentials profile  (SSL context / Kerberos / key)
acquisition.list (processor)       ──references──▶  connection
                                   ──references──▶  [optional credentials/SSL context]
```

**Migration:** today's `ConnectionProfile.Tunnel` and inline secret refs stay valid (back-compat); the new
*referenced* network/credentials profiles are additive — a connection may inline (as now) or `use:` a shared
profile. New connectors (S3/GCS/Kerberos, §8.4) land as credentials controller services + a connector.

## 3. The connection probe (the central new SPI)

**Gap:** [`ConnectionTester`](../inspecto/src/main/java/com/gamma/acquire/ConnectionTester.java) is honest
that it only does a **TCP reachability** check + secret-resolvability; protocol login and any
read/write/list is "Phase E, the connector itself". The user requirement — *"test the external system, even
a local directory: availability, read/write permission, list/traverse"* — needs a real probe that the
**connector** performs.

### 3.1 Shape — a graded probe on the `SourceConnector` SPI
Add one capability to the SPI (default = "not supported", so existing connectors compile unchanged):

```java
// com.gamma.acquire.SourceConnector
default ProbeResult probe(ProbeRequest req) { return ProbeResult.unsupported(); }
```

```java
record ProbeRequest(EnumSet<ProbeCheck> checks, int sampleLimit, DiscoveryContext listCtx) {}
enum ProbeCheck { REACHABILITY, AUTHENTICATE, READ_PERMISSION, WRITE_PERMISSION, LIST }
record ProbeResult(Map<ProbeCheck, CheckOutcome> checks, List<RemoteFile> sample, String detail) { ... }
record CheckOutcome(boolean ok, String detail) {}   // never throws; failures land here
```

- **REACHABILITY** keeps today's TCP check (or, for local, "directory exists").
- **AUTHENTICATE** = protocol login (SFTP handshake, JDBC `getConnection`, S3 `headBucket`).
- **READ/WRITE_PERMISSION** = can we read the base path / write a scratch sentinel and delete it. The
  **write** probe mirrors flow-graph-design.md §7.2 `sink/.../validate` ("scratch write, then discard").
- **LIST** = a bounded `discover()` over `listCtx` returning up to `sampleLimit` files (reuses the existing
  `discover` logic; this is also the **List processor's** preview, §6).

### 3.2 Implementations
- **`LocalFileSystemConnector`** (the "testable even for local directories" case): `Files.exists` +
  `Files.isReadable`/`isWritable` on `pollRoot`, a scratch-file write+delete for WRITE, and the existing
  `discover()` for LIST. No new dependency.
- **Remote connectors** (`inspecto-connectors`: SFTP/FTP/DB; future S3/GCS): each implements `probe()` with
  its client. Heavy deps stay confined to that module.
- **`ConnectionTester`** becomes the orchestrator: REACHABILITY itself (no client needed) + delegate the
  deeper checks to a connector built from the profile when one is on the classpath; degrade gracefully to
  `unsupported` when it is not (mirrors the existing "connectivity only" honesty).

### 3.3 API
Enrich, don't replace: `POST /components/connection/{id}/test` (exists, flow-graph-design.md §7.2 / §5.4)
takes an optional `{checks:[…], sampleLimit:N}` body and returns the full `ProbeResult` JSON. Default body =
today's reachability-only behaviour, so the endpoint is backward compatible.

## 4. Processor = Data Source, split List + Fetch

flow-graph-design.md §3.1 has **one** `acquisition` node ("file collector") that internally does
discover → stabilize → dedup → materialize. We split it into two node sub-types so each is independently
configurable and testable (NiFi `ListSFTP → FetchSFTP`):

| Node | NiFi | Does | Backed by |
|---|---|---|---|
| `acquisition.list` | `List*` | connect (via the connection controller service), **explore/permission-check/list/traverse** with the file pattern → candidate `RemoteFile`s. No bytes moved. | `SourceConnector.discover()` + §3 probe; `StabilityGate`/dedup/watermark filters |
| `acquisition.fetch` | `Fetch*` | take candidates → download/materialize → flowfile (batch) into the ingest path | `SourceConnector.open()/fetchTo()`, `RemoteAcquisitionHandler` |

- **`RemoteFile` already *is* the List→Fetch descriptor, and the ledger is the List state.** NiFi's
  `ListSFTP` emits zero-content flowfiles carrying attributes (path/size/mtime/perms) and tracks
  "already-listed" in its state manager; `FetchSFTP` (`INPUT_REQUIRED`) consumes those and pulls content.
  In Inspecto, [`RemoteFile`](../inspecto/src/main/java/com/gamma/acquire/RemoteFile.java) carries exactly
  those attributes, and `AcquisitionLedger` + incremental watermark + `StabilityGate` **are** the List
  processor's persistent state. So List = the stateful, `INPUT_FORBIDDEN` discover-half; Fetch = the
  `INPUT_REQUIRED` materialize-half — no new machinery, just naming two processors over what exists.
- The plain `acquisition` node stays as a **convenience = List+Fetch fused** (today's behaviour, the legacy
  lift target), so nothing existing breaks. The split is opt-in for users who want to test/schedule the
  explore step separately.
- **Many data sources per connection:** each `acquisition.list` references `use: connections/<id>` — N data
  sources from one SFTP host = N list/fetch pairs sharing one connection profile (the §2 dedup).
- The List/Fetch boundary is the natural seam already inside `SourceProcessor` (discover-half vs
  materialize-half); the executor compiles List+Fetch back to the same calls — **no engine rewrite**.

## 5. Per-data-source scheduling — already falls out

Requirement: *"each data source collected with a different frequency."* This needs **no new scheduler**: in
the flow model each data source is its **own flow** whose entry `acquisition.list` node carries a
[`FlowTrigger`](../inspecto/src/main/java/com/gamma/flow/FlowTrigger.java) (`schedule.every` / `schedule.cron`
/ `event` / `manual`; absent ⇒ `DEFAULT_POLL`). Different flows ⇒ different triggers ⇒ different
frequencies, driven by the existing two-scheduler split (flow-graph-design.md §3.8). What's left is the
flow-graph executor work already scheduled as that doc's **Phase 3** — this design adds no scheduler of its
own, it just confirms the data-source-per-flow shape is the right unit.

## 5A. Parallelism & concurrent tasks (single / multi instance)

### What exists today
Inspecto already parallelizes, but at **batch/source granularity, not per node**, and uniformly on virtual
threads + a `Semaphore` cap. Three caps **multiply**:

| Level | Cap | Where |
|---|---|---|
| across sources/pipelines | `sources.max` | [`MultiSourceProcessor`](../inspecto/src/main/java/com/gamma/inspector/MultiSourceProcessor.java) — 1 vthread per `PipelineConfig`, Semaphore-bounded |
| across batches in a source | `processing.threads` | [`SourceProcessor`](../inspecto/src/main/java/com/gamma/inspector/SourceProcessor.java) — `Semaphore(threads)` over a vthread executor |
| inside a batch (SQL) | `processing.duckdb_threads` | DuckDB morsel-driven query threads |
| inside fetch | `source.fetch.parallel_fetch` | [`RemoteAcquisitionHandler`](../inspecto/src/main/java/com/gamma/inspector/RemoteAcquisitionHandler.java) — a pool of N connector sessions |

Worker pressure ≈ `sources.max × threads × duckdb_threads`; a config-safety cross-check already warns when
that exceeds cores ([`ConfigSpecs`](../inspecto/src/main/java/com/gamma/config/spec/ConfigSpecs.java)). There
is **no per-node instance concept** yet — a batch flows through the reachable subgraph synchronously.

### The NiFi feature — per-node Concurrent Tasks
Every node gets a `concurrency: { max_tasks: N }` property — NiFi's "Concurrent Tasks". **Default `1`
(single instance).** As in NiFi, this is **one node definition with N concurrent worker tasks** (not N node
copies — the node logic must be parallel-safe), implemented by reusing the existing vthread+`Semaphore`
pattern scoped per-node. `acquisition.fetch`'s `parallel_fetch` **already is** this feature — surfaced
uniformly as the node's `max_tasks`.

```
  - id: list_cdr
    type: acquisition.list
    concurrency: { max_tasks: 1 }     stateful (watermark/ledger) so capped at 1
  - id: fetch_cdr
    type: acquisition.fetch
    concurrency: { max_tasks: 4 }     4 connector sessions fetch in parallel
  - id: parse_cdr
    type: parser
    concurrency: { max_tasks: 4 }     4 files parsed concurrently
```

Per node type, "multi-instance" parallelizes over different units: `fetch` over files (N sessions, exists);
`parser` over files/batches; `sink.persistent` over partitions (`PartitionWriter` parallel reveal exists);
`transform.*` compiles to DuckDB SQL, so its parallelism is `duckdb_threads` (morsel-driven) and a per-node
task count there is largely **redundant** — flagged so users don't double-count.

### When multi-instance helps — and when it hurts
- **Helps: skewed-volume data sources.** When a source's files/volumes are uneven, a single instance
  **serializes on the big unit** (one huge file stalls the cycle while lanes sit idle). N instances let the
  small units drain while the large ones process, balancing the cycle. The win is greatest on the
  **I/O-bound** nodes — `fetch` (network) and `parser` (per-file) — exactly the skew case.
- **Hurts: resource contention.** Multi-instance **multiplies into** the existing
  `sources × threads × duckdb_threads` budget; set carelessly it oversubscribes CPU / connections / memory
  and *slows* throughput. Guards:
  1. **Default 1** — opt in deliberately.
  2. **Node type declares its max** — a capability (like the relationships a type emits, flow-graph-design.md
     §3.2); **stateful types (`acquisition.list`) are capped at 1** (NiFi's stateful / primary-node-only
     rule), and the validator rejects `max_tasks > 1` on them.
  3. **Folds into the core-count guard** — the `ConfigSpecs` cross-field check must include node concurrency
     so total worker pressure stays bounded; the flow's non-overlapping `ingestLock` + admission budget
     (flow-graph-design.md §3.5) still applies, so a node never escapes the global cap.

### Honest divergence (the no-queue consequence)
Because there are no inter-node queues (§10), `max_tasks` is **data-parallelism over a node's work units
within a cycle**, not continuously-running, queue-decoupled worker instances: same per-processor
configurability and feel, but you do **not** get a buffer that lets a slow node avoid stalling a fast one.
"Execution: All Nodes vs Primary Node Only" (NiFi *clustering*) does not apply — Inspecto is single-JVM, so
multi-instance means intra-process concurrent tasks. Per-node concurrency lands with the flow-graph executor
(flow-graph-design.md Phase 3); until then `acquisition.fetch.parallel_fetch` is the working instance of it.

## 6. Testability ("each processor testable, provided predecessors ran")

Reuse flow-graph-design.md §7.2 verbatim — no divergent test path:
- **Connection** test = §3 probe (`POST /components/connection/{id}/test`).
- **`acquisition.list`** test = `preview` → run discovery once, return candidates + the probe outcome (no
  fetch). This is the "test connection / explore" the UI shows.
- **`acquisition.fetch`** test = `preview` → given **materialized List output** (the candidates from a List
  run, or a supplied sample), fetch a bounded sample to scratch and report. "Predecessors ran" = the List
  node's last output is available; `/flows/{id}/dry-run {fromNode, toNode}` runs a sub-chain.
- Batch model means "given predecessors ran" = *materialized upstream output*, not a live queue — exactly
  the locked semantics.

## 7. UI (reusable Test-Connection component)

- A **DBeaver-style connection dialog** (tabs: Main / driver props / SSH tunnel / SSL), built on the existing
  connections-CRUD pane (flow-graph-design.md §6, §5.4) and `ComponentFormDialog`.
- A **reusable `<connection-test>` component** rendering the `ProbeResult` (per-check ✓/✗ + sample listing +
  latency). **Reused** by the data-source (List) config screen for "test download/fetch", per the
  requirement. Backend (`ConnectionTester` + probe) is the single source.
- Follows the **angular-ui** skill rules (design-system components, no hardcoded colours, axe/WCAG gate).

## 8. Phasing

1. **Probe SPI + local/remote impls + enriched test endpoint** (§3) — self-contained, mostly over existing
   code. *Gate:* local dir R/W/list probe + SFTP probe (embedded sshd test) green.
2. **Connection dialog + reusable Test-Connection UI** (§7) on the probe.
3. **`acquisition.list` / `acquisition.fetch` node sub-types + per-node preview** (§4, §6) — rides the
   flow-graph executor (that doc's Phase 3); until then, expose List/Fetch as testable steps over
   `SourceProcessor`'s existing discover/materialize halves.
4. **New connectors** (S3, GCS, Kerberos auth) in `inspecto-connectors`, each implementing `probe()` — after
   the SPI is proven.

## 9. Open questions
1. **Probe SPI granularity** — one `probe(checks)` (this doc's recommendation) vs separate
   `checkPermission()` + `list()` methods. Leaning single graded method to keep the SPI small.
2. **Connectors repo split** — deferred; revisit once the SPI has absorbed S3/GCS/Kerberos and cross-product
   reuse is real (the module already isolates the deps).
3. **Write-permission probe side effects** — scratch sentinel name/location and cleanup guarantees
   (especially on read-only or quota'd remotes); must never leave litter.
4. **Data-source config file layout** — one `*_flow.toon` per data source vs a `sources:` list lifted into
   per-source flows; settle with the flow-graph Phase-3 authoring work.

## 10. The one deliberate non-adoption — the FlowFile session/queue runtime
We adopt NiFi's **authoring contracts** (controller service + processor, §1A) but **not** its runtime: no
inter-node **flowfile queues**, no get/commit/rollback **session**, no back-pressure, no per-flowfile
streaming. This is the locked flow-graph decision (a "FlowFile" = a batch/record-set). The consequences are
benign and already baked in above: "predecessors ran" = *materialized upstream output* (§6), not a live
queue; List→Fetch hand-off = a set of `RemoteFile` descriptors (§4), not a queued relationship; per-source
concurrency stays the existing fetch pool, not back-pressured connections. Provenance is the existing
data-plane overlay (flow-graph-design.md §11), not per-flowfile lineage events.

## 11. Reconciliation — no conflicts
Everything here is additive and lands inside the locked flow-graph model: Connection = referenced component
(controller service) + a richer probe; security/credentials = their own referenced services (§2.1); Data
Source = `acquisition` node, optionally split List/Fetch; scheduling = per-flow `FlowTrigger`; testing =
§7.2 dry-run. The lean-core, editions-as-build-flavors, no-`#`-comments, `ConfigSafetyValidator`, and
single-SemVer rules are unchanged.
