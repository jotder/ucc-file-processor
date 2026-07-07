# Re-prioritized plan — Connection Library (separate repo) + UI-first

## Context / decision (2026-06-24)

Reverse the earlier sequence. Instead of building the flow-graph **framework** first and the connection
layer inside it, we now build the **external-system connection layer first**, as a **standalone, reusable
Java library in its own repo**, with the **connection-management UI built first** (as a clickable, mocked
prototype). The flow-graph/processor **framework comes later** and will consume the library.

Confirmed decisions:
- **Repo shape:** a **pure Java library** (jar) in a new repo; the **UI lives in `inspecto-ui`** (the
  existing Angular app), not in the library repo.
- **"UI first":** build the connection-manager UI as a **clickable prototype against mocked**
  connect/test/explore/sample responses to nail the UX and freeze the API contract, then wire the real
  library behind it.
- **Code origin:** **port the proven classes** (they already have zero engine forward-deps), then extend.

North-star design stays [docs/acquire-controller-service-design.md](../../sandbox/ucc-file-processor/docs/acquire-controller-service-design.md);
"the framework" = its flow-graph executor work (that doc's Phase 3), now explicitly deferred behind this
library + UI.

## Library scope (the four verbs — and the boundary)

The library does exactly four things; **everything else (data sources, scheduling, flow graph) is the
deferred framework**:
1. **Connect** — establish a real session (protocol login: SFTP handshake / JDBC `getConnection` / S3
   `headBucket`), with connection reuse — not just a TCP ping.
2. **Explore** — permission-aware list/traverse of the resource (files/dirs; DB schemas/tables/columns).
3. **Test connection** — the graded probe (`REACHABILITY / AUTHENTICATE / READ / WRITE / LIST`),
   incl. local directories (design §3).
4. **Extract + collect sample data** — pull a **bounded** sample (first N files' head / first N rows of a
   table) for test/preview.

## Workstream A — Connection library (new repo, ported)

- New repo (name TBD; e.g. `inspecto-connect`). **Framework-free** (matches Inspecto ethos: JDK only +
  ServiceLoader SPI), small SBOM, **stable `@PublicApi` from day one** (Inspecto consumes it as a versioned
  artifact — the cross-repo coordination cost noted in the design doc).
- **Port** (zero engine forward-deps today, so liftable): `SourceConnector` SPI, `RemoteFile`,
  `DiscoveryContext`, `ConnectionProfile`, `ConnectionRegistry`, `SecretResolver`, `ConnectionTester`,
  `LocalFileSystemConnector`; the SFTP/FTP/DB connectors become the repo's optional connector modules.
- **Add** the new capability surface the UI needs (defines the contract):
  - `probe(ProbeRequest)` on the SPI (connect/explore/test) — design §3.
  - a `sample(SampleRequest)` capability (bounded extract) — new.
  - DTOs: `ConnectionSpec`, `ProbeResult`/`CheckOutcome`, `ResourceNode` (explore tree), `SampleResult`.
- Heavy/secure deps (S3/GCS/Kerberos/SSL) stay in their own optional connector modules.

## Workstream B — Connection UI in inspecto-ui (FIRST)

Per the **angular-ui** skill (design-system components, no hardcoded colours, axe/WCAG gate, signals state,
lazy routes). Reuse the existing connections-CRUD pane + `ComponentFormDialog` patterns.
- **Screens:** connection list; DBeaver-style connection editor (tabs: Main / driver props / SSH tunnel /
  SSL); **Test Connection** (per-check probe results + latency); **Explore** (resource tree browser);
  **Sample preview** (data grid).
- **B1 (first):** clickable prototype wired to **mocked** connect/test/explore/sample responses — validate
  UX and **freeze the API contract** (TS interfaces / OpenAPI).
- **B2:** swap mocks for real endpoints once the library + control routes exist.

## Sequence
1. **UI prototype (mocked)** — nail UX + API contract. *(first)*
2. **New repo scaffold + port + add probe/sample** to satisfy that contract.
3. **Wire**: Inspecto control API (`/components/connection/*`, explore/sample) delegates to the library; UI
   swaps mocks for real.
4. **(Later) framework** — flow-graph/processors/scheduling consuming the library.

## Verification
- Library: `mvn -o clean test` (offline, JDK 26, `--enable-native-access=ALL-UNNAMED`); connector probe
  tests via the embedded sshd/ftpserver harness already used by the connectors module; local R/W/list probe.
- UI: prototype renders against mock service; axe/WCAG + the no-hardcoded-colour CI guard pass; later, an
  ephemeral-port control-API test for the real endpoints.

## Open questions
1. New repo **name + Maven groupId/coordinates**.
2. Where the prototype's **API contract** is captured (TS interfaces vs OpenAPI) — it's the bridge between B1
   and B2.
3. **Explore depth for v1** — files/dirs only, or DB schemas/tables/columns too.
4. Sample size/limits and whether sample bytes are ever persisted (they should not be — preview only).
