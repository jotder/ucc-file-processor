# Common Mistakes

**⚠️ CRITICAL - Read at session start**

Fuller treatment in [docs/troubleshooting.md](../docs/troubleshooting.md).

---

## Top Critical Mistakes

### 1. `#` comments in TOON / ConfigCodec files

**Symptom**: Config parse failure / unexpected values in any `*_pipeline.toon`,
`*_connection.toon`, `*_rca.toon`, etc.
**Check**: Any line containing `#` in a JToon/ConfigCodec file.
**Fix**: Remove it. **No `#` comments are allowed** in JToon/ConfigCodec files.

### 2. Output directories nested inside the poll directory

**Symptom**: Startup validator aborts: `directory X must not be inside poll directory`.
**Check**: `database`, `backup`, `temp`, `errors`, `quarantine`, `markers` in the pipeline config.
**Fix**: Make them **siblings** of the poll dir, not nested — otherwise the ETL recurses into its
own output/scratch/marker space.

### 3. Forgetting `--enable-native-access=ALL-UNNAMED`

**Symptom**: Native-access warnings/failure launching the JAR (DuckDB).
**Check**: The `java` command line.
**Fix**: Always launch with `java --enable-native-access=ALL-UNNAMED ...`. The bundled
run/serve/ura scripts already include it.

### 4. All output lands in `year=NULL/month=NULL/day=NULL`

**Symptom**: Partition keys are all NULL.
**Cause**: The partition-key column value matches no configured date format.
**Fix**: Add the matching format to `date_formats` / `timestamp_formats` in the pipeline config.

### 5. Stale `.processed` markers / temp dir filling up

**Symptom (markers)**: File won't reprocess — a `.processed` marker exists in `markers/`
(mirrors the inbox tree, *not* in `inbox/`). Delete the marker to force reprocessing (auto-pruned
per `processing.duplicate_check.retention_days`).
**Symptom (scratch)**: `No space left on device` / OOM on a large file. DuckDB spills to
`dirs.temp` — point it at a roomy disk, set `processing.duckdb.temp_directory`/`memory_limit`/
`max_temp_directory_size`, or enable `processing.chunking`.

---

## Other gotchas

- **pg_duckdb view "column X does not exist"** — `read_parquet()` columns aren't visible to the PG
  planner. Every column must be explicitly aliased (`r['col']::type AS "col"`); use
  `generate_warehouse_views.py` to emit the DDL.
- **ORA-28002 junk lines leaking into data** — `skip_junk_lines` cap too low (Oracle
  password-expiry preamble). Raise the cap or set `-1` for unlimited scan.
- **DuckDB 1.1.1 Windows AVX2 crash** (`EXCEPTION_ACCESS_VIOLATION` in VCRUNTIME140.dll) — worked
  around by materializing a `transformed` table before `COPY TO`. Keep the workaround after any
  DuckDB upgrade.

---

## Environment & tooling (shared — so every profile on this sandbox has them)

> Consolidated here from per-profile session memory. Auto-memory lives under each Windows profile's
> `~/.claude/` and is **not** shared between teammates (e.g. `User` vs `jotder`); durable facts belong
> in these tracked sandbox docs/skills, not in memory.

- **`package.ps1` must run under pwsh 7, not Windows PowerShell 5.1** — the script is BOM-less UTF-8 and
  5.1 garbles it. The embedded JVM is a **jlinked, trimmed Windows runtime** with a curated module set;
  building "online" has gotchas — prefer offline. (See the `build-verify` skill.)
- **Theming plugin doesn't hot-reload** — editing the gamma Tailwind theming plugin
  (`@gamma/tailwind/plugins/theming.js`) or tokens requires a **dev-server restart**; verify via
  `getComputedStyle(body).getPropertyValue('--gamma-…')`. (See the `angular-ui` skill §5.)
- **`graphify` CLI is not installed** — the npm package of that name is an unrelated graphing library.
  The graphify PreToolUse hooks are gated on the binary being on PATH (silent until a real one exists);
  use Grep/Glob/Read or the `backend-explorer` agent. (See CLAUDE.md → graphify.)
- **Security/edition direction**: no Spring/Quarkus migration — harden in place as Personal/Standard
  editions with IAM-delegated (Keycloak/WSO2) OIDC resource-server auth. (See `docs/EDITIONS.md`.)

---

**Last Updated**: 2026-06-18
