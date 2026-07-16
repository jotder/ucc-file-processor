#!/usr/bin/env bash
# PostToolUse (Edit|Write) guard: when a DB-layer source file changes, remind the model to keep
# docs/okf/backend/engine/db-layer.md in sync. Advisory only — emits additionalContext, never blocks. The doc's exact
# DDL / store inventory / file topology / Postgres notes are derived from these files, so a change
# here that isn't mirrored in the doc silently rots it.
#
# Triggering files (basename match, any directory):
#   Db*Store.java        — DDL-bearing operational stores (objects/links/notes/status/jobs/provenance/ledger)
#   ParquetEventStore.java — the append-only event schema
#   ServiceStores.java   — the -D*.backend toggles + URL resolution
#   SpaceRoot.java       — per-space DuckDB file locations
#   JdbcDrivers.java / DuckDbUtil.java — connection factory + engine settings

input="$(cat)"
path="$(printf '%s' "$input" | python3 -c "import json,sys
try:
    d = json.load(sys.stdin); t = d.get('tool_input', d)
    print(str(t.get('file_path') or '').replace(chr(92), '/'))
except Exception:
    print('')" 2>/dev/null || true)"

base="${path##*/}"
case "$base" in
  Db*Store.java|ParquetEventStore.java|ServiceStores.java|SpaceRoot.java|JdbcDrivers.java|DuckDbUtil.java)
    cat <<'JSON'
{"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"DB-layer source changed. If this added/removed/altered a table's DDL or columns, a store's backend wiring, the per-space DuckDB file layout, a -D*.backend toggle, or Postgres behavior, update docs/okf/backend/engine/db-layer.md to match (§2 store inventory, §3 exact schemas, §4 file topology, §5 Postgres). Keep the DDL blocks byte-accurate to initSchema()."}}
JSON
    ;;
esac
exit 0
