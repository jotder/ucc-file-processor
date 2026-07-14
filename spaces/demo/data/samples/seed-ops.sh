#!/usr/bin/env bash
# Seed the demo space's OPERATIONAL objects through the REAL control routes (no direct DB writes):
# incidents (mail-UI priority ladder) with transitions + comments, two cases with linked members, and
# one evaluation of the shipped 'incident_burst' case rule so a rule-raised case appears.
# Run AFTER the backend is up. Durable across restarts only with -Dobjects.backend=db.
set -uo pipefail
BASE="${1:-http://localhost:8080}"
API="$BASE/spaces/${2:-demo}"

api() { # method path [json]
  local m="$1" p="$2" b="${3:-}"
  if [ -n "$b" ]; then
    curl -sS -f -X "$m" -H 'Content-Type: application/json' -d "$b" "$API$p" 2>/dev/null || echo ""
  else
    curl -sS -f -X "$m" "$API$p" 2>/dev/null || echo ""
  fi
}
id_of() { sed -n 's/.*"id"[: ]*"\([^"]*\)".*/\1/p' <<<"$1" | head -1; }

echo '1/3 Incidents (8) with transitions + comments...'
ids=()
n=0
while IFS='|' read -r title pri sev; do
  n=$((n+1))
  body="{\"type\":\"INCIDENT\",\"title\":\"$title\",\"description\":\"Seeded by seed-ops.sh - synthetic demo content.\",\"severity\":\"$sev\",\"priority\":\"$pri\",\"attributes\":{\"source\":\"orders\",\"category\":\"data-quality\"}}"
  created=$(api POST /objects "$body")
  oid=$(id_of "$created"); [ -z "$oid" ] && continue
  ids+=("$oid")
  [ $((n % 3)) -eq 0 ] && api POST "/objects/$oid/transition" '{"action":"accept","actor":"demo-seeder"}' >/dev/null
  [ $((n % 4)) -eq 0 ] && api POST "/objects/$oid/resolve" '{"actor":"demo-seeder"}' >/dev/null
  [ $((n % 2)) -eq 1 ] && api POST "/objects/$oid/comments" '{"author":"demo-seeder","body":"Matches the deliberately malformed rows in ORDERS_20260703.csv."}' >/dev/null
done <<'EOF'
Sequence gap in ORDERS feed|CRITICAL|CRITICAL
Reject rate above 2% in daily load|CRITICAL|CRITICAL
Duplicate file re-delivered|MAJOR|WARNING
Late file arrival from upstream SFTP|MAJOR|WARNING
Quantity outliers in WEST region|MINOR|WARNING
Backup verification took over 60s|MINOR|INFO
Enrichment lag behind ingest|LOW|INFO
Marker file left behind after crash|LOW|INFO
EOF

echo '2/3 Cases (2) with linked incident members...'
case_ids=()
for c in 'Orders feed reliability review' 'Data-quality deep dive: quantity fields'; do
  created=$(api POST /objects "{\"type\":\"CASE\",\"title\":\"$c\",\"description\":\"Seeded by seed-ops.sh.\",\"priority\":\"MAJOR\"}")
  cid=$(id_of "$created"); [ -n "$cid" ] && case_ids+=("$cid")
done
if [ "${#case_ids[@]}" -gt 0 ]; then
  for k in 0 1 2 3; do
    [ "$k" -lt "${#ids[@]}" ] || break
    api POST "/objects/${case_ids[$((k % ${#case_ids[@]}))]}/links" \
        "{\"to\":\"${ids[$k]}\",\"relationship\":\"related\",\"actor\":\"demo-seeder\"}" >/dev/null
  done
fi

echo '3/3 Evaluating the shipped incident_burst case rule (rule-raised case)...'
api POST /cases/rules/incident_burst/evaluate '{}' >/dev/null

echo "Seeded demo ops surface: ${#ids[@]} incidents, ${#case_ids[@]} authored cases (+ any rule-raised)."
echo "UI: Incidents + Case Manager now have mail-view data; CRITICAL incidents carry the 'hot' tag."
