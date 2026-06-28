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

* `SourceConnector` (`inspecto/src/main/java/com/gamma/acquire/SourceConnector.java`) — `discover`,
  `readiness`, `open` (stream bytes), `fetchTo` (materialise straight to the backup dir, never temp-then-move),
  `post` (RETAIN/DELETE/MOVE/RENAME/TAG), and a `Capability` enum.
* `SourceConnectorFactory` (`…/acquire/SourceConnectorFactory.java`) — `scheme()` + `create(cfg, profile)`,
  registered via `META-INF/services`.

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
  `base_path`, `username`, `password`, an `options` map, and an optional `tunnel` sub-block.
* **Secrets are never literals** — `SecretResolver` (`com/gamma/acquire/SecretResolver.java`) resolves
  `${ENV:VAR}` / `${SYS:prop}` / `${NAME}` at connect time, never at load; `isResolvable()` powers the test
  endpoint without exposing values.
* `ConnectionRegistry` (`com/gamma/acquire/ConnectionRegistry.java`) bridges the service layer (owns the toon
  files) to the static poll-cycle path, keyed per `(spaceId, profileId)`.
* Control routes: `GET /connections`, `GET /connections/{id}`, `POST /connections/{id}/test` (TCP reachability
  + latency + secret-resolvability) — surfaced as the UI Connections "Test" action.
