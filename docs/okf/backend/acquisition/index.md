# Acquisition

The data-acquisition engine that feeds the [engine](../engine) — discover, gate, dedup, retrieve, finalize —
plus the remote [connectors](../modules/connectors.md). All six roadmap phases (A–F) ship on `4.x`.

# Concepts

* [Framework](framework.md) - the poll cycle and phases A–F: discovery, stability gate, dedup/watermark ledgers, gap detection, retry + circuit breaker.
* [Connectors](connectors.md) - the `SourceConnector` SPI and the SFTP/FTP/FTPS/DB connectors, SSH tunnelling, connection profiles, secret resolution.
* [Data-acquisition framework (full design)](data-acquisition-framework.md) - the complete framework doc (moved from `docs/data_acquisition_framework.md`).
