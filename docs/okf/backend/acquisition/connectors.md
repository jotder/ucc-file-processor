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

* **SFTP** — sshj session; SSH host-key pinning (`host_key` / `known_hosts` / `strict_host_key`); bastion via `SshTunnel`.
* **FTP/FTPS** — passive/binary; FTPS `tls: explicit|implicit` (`PBSZ 0` + `PROT P`); tunnelling needs `options.passive_ports`.
* **DB export** — runs a date-templated JDBC query, materialises CSV; row-level watermark mode binds
  `:watermark` (injection-safe) and advances only after batch commit (at-least-once on crash).
* **`SshTunnel`** (`…/connectors/SshTunnel.java`) — an `AutoCloseable` SSH TCP-forward used by all three remote connectors.

## Profiles, secrets, registry

* Connection profiles are `<name>_connection.toon` (`ConnectionProfile`): `id`, `connector`, `host`, `port`,
  `base_path`, `username`, `password`, an `options` map, an optional `tunnel` sub-block, and an optional
  `proxy` sub-block (`type` HTTP|SOCKS5, `host`, `port`, `username`, `password` — added 2026-07-18; the UI
  authored it first). The proxy hop is probed by `POST /connections/test?target=proxy`; connectors do not
  dial through it yet, and it deliberately does not change `testEndpoint()` (the saved-profile test still
  prioritises the tunnel hop, else the target).
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
* **Not implemented** (report as skipped/501): `db`, `s3`, `gcs`, `azure`, `kafka` workbenches — each can adopt
  the same factory hook when demanded; DB explore should present schema/table/column `ResourceNode`s.
* Historical route naming: the archived `acquire-controller-service-design.md` §3 proposed enriching
  `POST /components/connection/{id}/test` instead — the UI's frozen `/connections/{id}/probe|explore|sample`
  paths won (the plan predates the UI contract).
