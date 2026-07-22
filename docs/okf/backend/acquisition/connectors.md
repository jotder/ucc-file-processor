---
type: Concept
title: Source Connectors
description: The SourceConnector SPI and the SFTP/FTP/FTPS/DB connectors, SSH tunnelling, profiles, and secret resolution.
resource: inspecto-connectors/src/main/java/com/gamma/acquire/connectors
tags: [acquisition, connectors, sftp, ftp, jdbc, ssh-tunnel, secrets]
timestamp: 2026-06-28T00:00:00Z
---

# Source Connectors

## The SPI (in the core; implemented in [connectors](../modules/connectors.md))

* `CollectorConnector` (`inspecto/src/main/java/com/gamma/acquire/CollectorConnector.java`) — `discover`,
  `readiness`, `open` (stream bytes), `fetchTo` (materialise straight to the backup dir, never temp-then-move),
  `post` (RETAIN/DELETE/MOVE/RENAME/TAG), and a `Capability` enum. *(Renamed from `SourceConnector` per the
  Source→Collector glossary flip.)*
* `CollectorConnectorFactory` (`…/acquire/CollectorConnectorFactory.java`) — `scheme()` + `create(cfg, profile)`
  + the optional `workbench(profile)` hook (below), registered via `META-INF/services`.
* `ConnectionWorkbench` (`…/acquire/ConnectionWorkbench.java`) — the graded probe / explore / sample SPI
  behind the connection-workbench routes (below); implementations hold one session per instance, like a
  connector.

## Concrete connectors (`inspecto-connectors/`)

| Class | Scheme | Library |
|---|---|---|
| `SftpConnector` | `sftp` | sshj + BouncyCastle |
| `FtpConnector` | `ftp` / `ftps` | Apache commons-net |
| `DbExportConnector` | `db` | JDBC (Postgres driver) |
| `S3Connector` | `s3` | **SDK-free** — raw REST + hand-rolled `AwsSigV4` |
| `AzureBlobConnector` | `azure` | **SDK-free** — raw REST + hand-rolled `AzureSharedKey` |
| `GcsConnector` | `gcs` | **SDK-free** — raw REST + `GcpServiceAccountToken` (OAuth2) |
| `KafkaConnector` | `kafka` | kafka-clients |

* **SFTP** — sshj session; SSH host-key pinning (`host_key` / `known_hosts` / `strict_host_key`); bastion via `SshTunnel`.
* **FTP/FTPS** — passive/binary; FTPS `tls: explicit|implicit` (`PBSZ 0` + `PROT P`); tunnelling needs `options.passive_ports`.
* **DB export** — runs a date-templated JDBC query, materialises CSV; row-level watermark mode binds
  `:watermark` (injection-safe) and advances only after batch commit (at-least-once on crash).
* **`SshTunnel`** (`…/connectors/SshTunnel.java`) — an `AutoCloseable` SSH TCP-forward used by the three remote connectors above.

### Object storage — deliberately SDK-free (ACQ-4)

All three object-storage connectors hand-roll their cloud's auth on plain JDK crypto (`javax.crypto.Mac`,
`MessageDigest`, `java.security.Signature`) and talk raw REST over `java.net.http.HttpClient` — **no cloud SDK
jar anywhere**, keeping the module's SBOM small and the build air-gappable. Each maps a `*_connection.toon`
profile the same way: `base_path` = `bucket-or-container[/prefix]`; `password` = a `SecretResolver` reference
resolved per use, never logged; `host`/`port`/`options.protocol` override the endpoint (for MinIO/Azurite/tests).
All advertise `STREAM, RANDOM_ACCESS, RESUMABLE, DELETE, MOVE, RENAME, TAG, ETAG`; MOVE/RENAME are copy+delete
(object stores have no rename); listings carry each object's ETag onto `RemoteFile.etag()` for ACQ-7 etag dedup;
a listed object is atomic ⇒ `readiness` is always `READY`.

* **S3** (`S3Connector` + `AwsSigV4`) — S3 REST + SigV4 header signing, path-style addressing. Covers AWS S3,
  MinIO, and **GCS in interoperability mode** (S3-compatible XML API + HMAC keys). `username`/`password` =
  access-key-id / secret-key; `options.region` (default `us-east-1`).
* **Azure Blob** (`AzureBlobConnector` + `AzureSharedKey`) — Blob REST + Shared Key signing. `username` = storage
  account; `password` = account key (base64). Covers real Azure + the Azurite emulator.
* **GCS native** (`GcsConnector` + `GcpServiceAccountToken`, shipped 2026-07-22) — the GCS **JSON API**
  (`/storage/v1/…`) authenticated by a **service-account OAuth 2.0 bearer token**. This is the distinct
  *native* path vs. the S3-interop route above: a native GCS deployment issues a service-account JSON key, not
  interop HMAC credentials. `password` = the service-account key file *content* (typically
  `${FILE:/secure/gcs-sa.json}`); `options.scope` (default `devstorage.read_write`). The auth helper builds an
  RS256-signed JWT assertion (`SHA256withRSA` over a PKCS#8 key parsed from the SA JSON), exchanges it at the
  SA's `token_uri` for a bearer token, and caches that token until ~60s before expiry (one mint per scan cycle).
  Listings come from Objects:list (paginated via `nextPageToken`), the object `generation` → `RemoteFile.version`,
  and TAG maps to GCS custom object metadata (a metadata PATCH), the native equivalent of S3 object tags. JSON is
  parsed with gson (parent-managed; already transitively on the classpath — no new fat-JAR jar). The
  **"offline-blocked (no SDK jars)" label ACQ-4 carried for GCS-native was stale**: OAuth2 JWT signing is the
  same category of hand-rollable JDK crypto as SigV4/SharedKey, needs no SDK.

## Profiles, secrets, registry

* Connection profiles are `<name>_connection.toon` (`ConnectionProfile`): `id`, `connector`, `host`, `port`,
  `base_path`, `username`, `password`, an `options` map, an optional `tunnel` sub-block, and an optional
  `proxy` sub-block (`type` HTTP|SOCKS5, `host`, `port`, `username`, `password` — added 2026-07-18; the UI
  authored it first). The proxy hop is probed by `POST /connections/test?target=proxy`, and it deliberately
  does not change `testEndpoint()` (the saved-profile test still prioritises the tunnel hop, else the
  target). **2026-07-20 SHIPPED first dial-through: `SftpConnector` only.** A `SOCKS5` proxy routes the
  real SFTP connect via `SocksProxySocketFactory` (a `javax.net.SocketFactory` wrapping a `java.net.Proxy` —
  sshj's `SocketClient.connect(host, port)` already calls `socketFactory.createSocket()` then
  `socket.connect(target, timeout)` on the result, so a plain JDK socket built on a SOCKS `Proxy` tunnels
  transparently with no protocol handshake of our own); ignored when an SSH bastion `tunnel` is also
  configured (the tunnel already rewrites the dial target to a local loopback forward). `HTTP` proxy type
  is rejected fail-closed for SFTP — a JDK socket can't transparently CONNECT-tunnel an arbitrary protocol
  the way it can for SOCKS, so this needs its own handshake, not yet built. **Still not dialing through
  any proxy:** FTP/FTPS (`commons-net` `FTPClient` needs its own socket-factory-equivalent wiring) and the
  JDBC-based connectors (proxying is driver-URL-param territory, not a uniform hook).
* **Secrets are never literals** — `SecretResolver` (`com/gamma/acquire/SecretResolver.java`) resolves
  `${ENV:VAR}` / `${SYS:prop}` / `${FILE:/path}` / `${KEYSTORE:alias}` / `${NAME}` at connect time, never at
  load; `isResolvable()` powers the test endpoint without exposing values. `${FILE:…}` reads a mounted secret
  file (Docker/K8s idiom; one trailing newline stripped). `${KEYSTORE:alias}` reads a `SecretKeyEntry` from a
  Java KeyStore located by `-Dsecrets.keystore.path` / `-Dsecrets.keystore.type` (default `JCEKS`) /
  `-Dsecrets.keystore.password` (itself a reference, so the store password need not be in the clear) — the
  pure-JDK, OS-independent keystore option (SEC-8); a Vault scope is a future addition (client not in the lean core).
* `ConnectionRegistry` (`com/gamma/acquire/ConnectionRegistry.java`) bridges the service layer (owns the toon
  files) to the static poll-cycle path, keyed per `(spaceId, profileId)`.
* Control routes: `GET /connections`, `GET /connections/{id}`, `POST /connections/{id}/test` (TCP reachability
  + latency + secret-resolvability) — surfaced as the UI Connections "Test" action. `POST /connections/test`
  probes an *unsaved* profile from the create/edit form; `?target=connection|tunnel|proxy` picks the hop.

## Connection workbench (probe · explore · sample — shipped 2026-07-18)

The graded workbench surface the UI froze mock-first (`connection-probe.service.ts`) is now served for real:

* **Routes** (`ConnectionRoutes`): `POST /connections/{id}/probe` (body `{checks?, sampleLimit?}`),
  `GET /connections/{id}/explore?path=`, `GET /connections/{id}/sample?path=&limit=`. Gates: 404 unknown id ·
  422 unknown check name · 400 missing sample path · **403 path escape (jail)** · 404 unknown path ·
  501 connector without workbench support · 502 protocol failure. Read-only, no capability gate (same as `/test`).
* **Orchestration** (`ConnectionProber`): reachability + `secretsResolved` are answered generically via
  `ConnectionTester`; the graded checks (authenticate/read/write/list) go to the profile's
  `ConnectionWorkbench`. Unreachable ⇒ graded checks *skipped* (`"not attempted"`); no workbench ⇒ *skipped*
  (`"not supported by the '<scheme>' connector yet"`) — **a probe never fabricates a check it didn't run**.
* **Local** (`LocalConnectionWorkbench`, built-in): jailed under `base_path`; WRITE = scratch write + delete;
  sample via `FileSampler` — the production DuckDB readers (`read_csv` auto-detect all-varchar /
  `read_parquet` / `read_json_auto`) on a throwaway scratch DB, raw-line fallback for non-tabular files.
* **SFTP / FTP / FTPS** (`AbstractRemoteWorkbench` + nested `Workbench` classes in the two connectors):
  AUTHENTICATE opens the real session; explore is a lazy single-level listing; **sample fetches the whole
  file to a throwaway temp dir only when the listed size is ≤ 8 MiB** (`SAMPLE_FETCH_CAP`) — larger or
  size-less files are refused with an honest detail, because an FTP data stream cannot be abandoned
  mid-transfer safely (`completePendingCommand`). Remote paths are jailed segment-wise (no absolute paths,
  no `..` climbing above the base).
* **DB** (`DbConnectionWorkbench`, shipped 2026-07-18): implements `ConnectionWorkbench` directly (not the
  remote-file skeleton — it's a schema tree, not a directory tree). AUTHENTICATE opens a JDBC connection;
  READ/LIST count schemas/tables via `DatabaseMetaData`; **WRITE is always *skipped*** — a workbench never
  mutates a database to prove write access. Explore walks `schema → table → column` (`ResourceNode.Kind`
  `SCHEMA`/`TABLE`/`COLUMN`); sample runs `SELECT *` with a vendor-neutral `setMaxRows(limit+1)` cap over the
  identifier-quoted `schema.table` (so a crafted name can't break out of the query), reusing `JdbcRows`. The
  JDBC connect/URL/tunnel/secret logic is shared with `DbExportConnector` via the package-private
  `DbConnections.open(profile)` helper.
* **Not implemented** (report as skipped/501): `s3`, `gcs`, `azure`, `kafka` workbenches — each can adopt
  the same `CollectorConnectorFactory.workbench` hook when demanded.
* Historical route naming: the archived `acquire-controller-service-design.md` §3 proposed enriching
  `POST /components/connection/{id}/test` instead — the UI's frozen `/connections/{id}/probe|explore|sample`
  paths won (the plan predates the UI contract).
