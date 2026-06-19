#!/usr/bin/env bash
# Install / upgrade and verify the graphify knowledge-graph CLI for this repo (POSIX / Git-Bash).
#
# "graphify" here is the Python *graphifyy* package (PyPI) — NOT the unrelated npm package.
# This makes the `graphify` CLI available on the CURRENT USER's PATH (no admin) and refreshes
# the repo's knowledge graph in graphify-out/. Run once per user working in this directory.
#
# graphify-out/ (the graph) and .claude/ (the query-first enforcement hooks) are gitignored but
# shared on-disk in this sandbox, so they already apply to everyone here — only the CLI is per-user.
#
# Usage:
#   bash scripts/setup-graphify.sh             # install/upgrade + verify + refresh graph
#   bash scripts/setup-graphify.sh --no-update # just install + verify
#   bash scripts/setup-graphify.sh --rebuild   # force a full deterministic re-extract
set -euo pipefail

NO_UPDATE=0
REBUILD=0
for arg in "$@"; do
  case "$arg" in
    --no-update) NO_UPDATE=1 ;;
    --rebuild)   REBUILD=1 ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

have() { command -v "$1" >/dev/null 2>&1; }
section() { printf '\n=== %s ===\n' "$1"; }

# Repo root = parent of this script's dir.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
echo "Repo: $REPO_ROOT"

section "1/4  Install or upgrade graphify (PyPI package: graphifyy)"
already_on_path=0
if have graphify; then
  already_on_path=1
  echo "Found graphify on PATH: $(command -v graphify)"
fi

install_ok=0
{
  if have uv;      then echo "Installer: uv tool";       uv tool install --upgrade graphifyy && install_ok=1
  elif have pipx;  then echo "Installer: pipx";          { pipx install graphifyy || true; pipx upgrade graphifyy || true; } && install_ok=1
  elif have pip;   then echo "Installer: pip";           pip install --upgrade graphifyy && install_ok=1
  elif have pip3;  then echo "Installer: pip3";          pip3 install --upgrade graphifyy && install_ok=1
  elif have python;then echo "Installer: python -m pip"; python -m pip install --upgrade graphifyy && install_ok=1
  elif have python3; then echo "Installer: python3 -m pip"; python3 -m pip install --upgrade graphifyy && install_ok=1
  else echo "No Python installer found (need uv, pipx, or pip). Install Python 3.10+ first." >&2; exit 1
  fi
} || {
  if [ "$already_on_path" = 1 ]; then
    echo "WARNING: install/upgrade failed (offline?). Continuing with the existing graphify." >&2
  else
    echo "ERROR: could not install graphify and none is on PATH." >&2; exit 1
  fi
}

section "2/4  Verify the CLI is on PATH"
if ! have graphify; then
  echo "WARNING: graphify installed but not on PATH." >&2
  if have python; then
    sd="$(python -c "import sysconfig; print(sysconfig.get_path('scripts'))" 2>/dev/null || true)"
    [ -n "$sd" ] && echo "Add this directory to your PATH, then re-run:  $sd"
  fi
  echo "ERROR: graphify not on PATH." >&2; exit 1
fi
echo "OK: $(graphify --version 2>&1 | head -1)"
graphify --help >/dev/null

# Sync the /graphify skill + CLAUDE.md registration into this user's agent platform.
# Idempotent and non-destructive; keeps the skill in lockstep with the CLI after an upgrade.
if graphify install >/dev/null 2>&1; then
  echo "Skill synced into your agent platform (~/.claude)."
else
  echo "WARNING: skill sync (graphify install) skipped." >&2
fi

section "3/4  Knowledge graph (graphify-out/)"
if [ -f graphify-out/graph.json ]; then
  if [ "$NO_UPDATE" = 1 ]; then
    echo "graph.json present; --no-update set — skipping refresh."
  elif [ "$REBUILD" = 1 ]; then
    echo "Forcing a full deterministic re-extract..."; graphify update . --force
  else
    echo "Refreshing the graph from current code (deterministic, no LLM)..."; graphify update .
  fi
else
  echo "No graphify-out/graph.json yet — a first build needs one LLM pass."
  echo "In Claude Code, run the skill once:   /graphify ."
  echo "After that, 'graphify update .' (or this script) keeps it fresh with no LLM cost."
fi

section "4/4  Ready"
cat <<'EOF'
graphify is set up for this user. Orient before grepping/reading source:
  graphify query "how does the flow executor commit branches"
  graphify explain "ConfigRegistry"
  graphify path "FlowExecutor" "DuckDB"

Keep the graph fresh after code changes:  graphify update .
Wire the /graphify skill into your own agent platform (optional):  graphify install --platform claude
EOF
