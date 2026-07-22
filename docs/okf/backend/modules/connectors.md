---
type: Module
title: Connectors Module (inspecto-connectors/)
description: Remote source connectors (SFTP/FTP/FTPS/DB) and all network dependencies, kept out of the core.
resource: inspecto-connectors/
tags: [module, connectors, network, sftp, ftp, jdbc]
timestamp: 2026-06-28T00:00:00Z
---

# Connectors Module (`inspecto-connectors/`)

artifactId `file-processor-connectors`. Holds **all network dependencies** (sshj + BouncyCastle for SFTP,
Apache commons-net for FTP/FTPS, the PostgreSQL JDBC driver) so the [engine core](engine.md) JAR has none.

Drop this jar on the classpath and `ServiceLoader` auto-discovers the `CollectorConnectorFactory` providers;
without it, only the built-in `local` connector works. Future connectors (S3, NFS/SMB) plug in via the same
SPI without touching the core. See [Connectors](../acquisition/connectors.md) for the classes and the
`SshTunnel` bastion support.
