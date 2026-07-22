---
type: Concept
title: TOON Configuration
description: ConfigCodec (JToon), the three config file types, PipelineConfigParser, and the tabular-array serialization gotcha.
resource: inspecto-config/src/main/java/com/gamma/config/io/ConfigCodec.java
tags: [config, toon, jtoon, parser, gotcha]
timestamp: 2026-06-28T00:00:00Z
---

# TOON Configuration

All configuration is **TOON** (`.toon`), parsed via JToon. Authoritative key reference: [`configuration.md`](configuration.md).

* **`ConfigCodec`** (`inspecto-config/src/main/java/com/gamma/config/io/ConfigCodec.java`) — thin JToon wrapper:
  `toMap` (lenient, tolerates `#` comments), `toMapStrict` (canonical assertion), `toToon` (canonical encode).
  **Gotcha**: `toToon` does **not** emit tabular-array format — a Java-constructed schema whose `fields`/
  `rules` are `List<Map>` round-trips as nested maps and the parser then throws *"Array length mismatch:
  declared N, found 0"*. Write test schemas as inline TOON strings, not via `toToon(schemaMap)`; round-trip is
  only safe when the map was originally `JToon.decode`-d. See [gotchas](../gotchas/cross-cutting.md).
* **`PipelineConfigParser`** (`inspecto-etl/src/main/java/com/gamma/etl/PipelineConfigParser.java`,
  package-private) — parses a decoded map into an immutable `PipelineConfig` (entry points
  `PipelineConfig.load(path)` / `fromMap(map)`). Pure parse, no filesystem side-effects (`prepare()` does
  those). Resolves `schema_file`/`grammar`/`dirs.*` against the **JVM CWD** (not the space root — see
  [gotchas](../gotchas/cross-cutting.md)).

## The three config file types per source

| File | Key groups |
|---|---|
| `<src>_gen.toon` | `csv_settings` (delimiter, engine, skip_* lines), `type_patterns` (dates/timestamps) |
| `<src>_schema.toon` | `raw.fields[]` (name/selector/type), `mapping.rules[]` (targetColumn/sourceExpression/transformType), `partitions[]` |
| `<src>_pipeline.toon` | `name`, `active`, `dirs.*`, `output.format/compression`, `processing.*` (threads, batch, csv_settings, schema_file, streaming, ingester/segments), `source:` acquisition block |

No `#` comments are allowed in files the strict parser handles. Writes go through
[`ConfigSafetyValidator`](config-safety.md).
