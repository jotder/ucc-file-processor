#!/bin/bash
# session-start-context.sh
# EVENT: SessionStart
# Repo-first protocol reminder + last session snapshot (shared team sandbox, shift handover).
python3 - <<'PY'
import json, os

ctx = (
    "Repository-first protocol (see CLAUDE.md): this checkout is a SHARED TEAM SANDBOX worked in "
    "shifts; conversation context is temporary, the repo is the source of truth. Resume from "
    "SESSION_STATUS.local.md (live working state) and docs/. Reference maps in .claude/ "
    "(COMMON_MISTAKES, QUICK_START, ARCHITECTURE_MAP) are on-demand, not auto-loaded. "
    "At shift end run /handoff and END the session — session-per-shift, no mid-task compaction."
)
snap = ".claude/sessions/snapshot.md"
if os.path.isfile(snap):
    try:
        with open(snap, encoding="utf-8", errors="replace") as f:
            ctx += "\n\nLast session snapshot (.claude/sessions/snapshot.md):\n" + f.read()[:1500]
    except OSError:
        pass
print(json.dumps({"hookSpecificOutput": {"hookEventName": "SessionStart", "additionalContext": ctx}}))
PY
