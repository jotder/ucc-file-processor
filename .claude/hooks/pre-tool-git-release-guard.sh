#!/bin/bash
# pre-tool-git-release-guard.sh
# EVENT: PreToolUse (matcher: Bash)
# DESCRIPTION: Enforce the branch & release policy (docs/BRANCHING.md) on git commit/push/tag.
#   1. HARD-BLOCK (exit 2) any commit/push/tag on a RETIRED/EOL line (1.x, 2.x, 3.x), and any
#      push whose target ref is a retired line.
#   2. On any commit/push/tag, INJECT the mandatory merge-forward propagation checklist as
#      additionalContext so the agent runs it and asks the user to confirm before pushing.
#
# CONFIGURE:
#   UCC_RELEASE_GUARD_DISABLE=1            — bypass all checks (human-only override for EOL backports)
#   UCC_RETIRED_BRANCHES="1.x 2.x 3.x"     — override the retired set

if [ "${UCC_RELEASE_GUARD_DISABLE:-0}" = "1" ]; then
  exit 0
fi

# Only intercept Bash tool calls.
if [ "${CLAUDE_TOOL_NAME:-Bash}" != "Bash" ]; then
  exit 0
fi

CMD=$(cat | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    print(d.get('tool_input', {}).get('command', ''))
except Exception:
    pass
" 2>/dev/null)

[ -z "$CMD" ] && exit 0

# Act only on state-changing git verbs (ignore status/log/diff/etc.).
echo "$CMD" | grep -qE '\bgit\b([^|;&]*\b(commit|push|tag)\b)' || exit 0

RETIRED="${UCC_RETIRED_BRANCHES:-1.x 2.x 3.x}"
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null)

# ── HARD BLOCK 1: on a retired/EOL line ───────────────────────────────────
for r in $RETIRED; do
  if [ "$BRANCH" = "$r" ]; then
    echo "🚫 Release guard: '$BRANCH' is RETIRED / end-of-life (see docs/BRANCHING.md)." >&2
    echo "   No commits, pushes, or tags are allowed on 1.x / 2.x / 3.x." >&2
    echo "   Supported lines: the current N.x maintenance branch + master." >&2
    echo "   A genuine security backport to an EOL line requires a HUMAN to set" >&2
    echo "   UCC_RELEASE_GUARD_DISABLE=1 for that command and document the exception." >&2
    exit 2
  fi
done

# ── HARD BLOCK 2: pushing to a retired target ref from anywhere ───────────
for r in $RETIRED; do
  if echo "$CMD" | grep -qE "git[[:space:]]+push([[:space:]]+[^[:space:]]+)*[[:space:]]+(HEAD:)?${r}([[:space:]:]|$)"; then
    echo "🚫 Release guard: pushing to retired/EOL branch '$r' is not allowed (docs/BRANCHING.md)." >&2
    exit 2
  fi
done

# ── REMINDER: inject the mandatory propagation checklist ──────────────────
MSG="MANDATORY branch & release policy — you are on branch '${BRANCH:-?}'. Before this git commit/push/tag you MUST run the propagation checklist (docs/BRANCHING.md) and ASK the user to confirm the merge-forward set BEFORE pushing: (1) Classify the change as a Conventional Commit type (fix:->PATCH, feat:->MINOR, feat!/BREAKING->MAJOR) -> sets the target line. (2) Find the OLDEST still-supported branch the change affects. If it is a fix: and you are on a NEWER line (e.g. master) while the bug also exists in the current N.x line, STOP and relocate the fix down to that older branch first. (3) Propagation is MERGE-FORWARD: commit the fix on the oldest supported affected branch, then merge forward up to master (e.g. 4.x -> master). feat: goes to master only. (4) Enumerate EVERY supported branch that still needs this change and ASK THE USER to confirm the set, then execute the merges to master. (5) NEVER commit/push/tag on retired lines 1.x/2.x/3.x. (6) Editions (Personal/Standard/Enterprise) are BUILD FLAVORS via Maven profiles + ServiceLoader modules, NEVER branches. (7) Tag releases vX.Y.Z (SemVer) on the branch they ship from; one version spans all editions. Do not push until the propagation set is confirmed with the user."

python3 -c "
import json, sys
print(json.dumps({'hookSpecificOutput': {'hookEventName': 'PreToolUse', 'additionalContext': sys.argv[1]}}))
" "$MSG"

exit 0
