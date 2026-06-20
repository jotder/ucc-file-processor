#!/usr/bin/env bash
# Run one serve-mode Inspecto example: start the engine as a service (Control API), seed its
# inbox, probe the API, then stay running so you can play with it.
#
# Unlike run-example.sh (one-shot batch), this starts com.gamma.control.ControlApi over the
# example's config dir on a short poll interval, waits for GET /health, seeds a fresh out/inbox/
# from the pristine samples/, waits a couple of poll cycles, then probes the Control API: generic
# probes (/pipelines, /events) plus any paths listed in the example's probes.txt. By default the
# server keeps running for exploration (press Enter to stop); --demo prints the probes once and
# stops (non-interactive, self-checking).
#
# Serve-mode examples use the *_pipeline.toon / *_enrich.toon / *_job.toon naming the engine scans
# for, and the engine runs with CWD = the example dir, so relative paths (schema_file, dirs.poll)
# resolve exactly as in one-shot mode. Everything is written under the example's own out/.
#
# JAR resolution: $INSPECTO_JAR -> ../file-processor.jar (bundle) -> ../target/file-processor-*.jar.
#
# Usage: bash serve-example.sh 06-serve/sequence-gap [--demo] [--port N] [--poll N] [--wait N] [--clean]
set -euo pipefail
EXAMPLES_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT=18080; POLL=3; WAIT=0; DEMO=0; CLEAN=0; EX=""
while [ $# -gt 0 ]; do
  case "$1" in
    --port)  PORT="$2"; shift 2;;
    --poll)  POLL="$2"; shift 2;;
    --wait)  WAIT="$2"; shift 2;;
    --demo)  DEMO=1; shift;;
    --clean) CLEAN=1; shift;;
    -h|--help) echo "usage: serve-example.sh <example-dir> [--demo] [--port N] [--poll N] [--wait N] [--clean]"; exit 0;;
    *) EX="$1"; shift;;
  esac
done
[ -n "$EX" ] || { echo "usage: serve-example.sh <example-dir> [--demo] [--port N] [--poll N] [--wait N] [--clean]" >&2; exit 2; }

resolve_jar() {
  if [ -n "${INSPECTO_JAR:-}" ] && [ -f "$INSPECTO_JAR" ]; then echo "$INSPECTO_JAR"; return; fi
  if [ -f "$EXAMPLES_ROOT/../file-processor.jar" ]; then echo "$EXAMPLES_ROOT/../file-processor.jar"; return; fi
  local t; t="$(ls "$EXAMPLES_ROOT"/../target/file-processor-*.jar 2>/dev/null | grep -Ev 'sources|javadoc' | head -1 || true)"
  if [ -n "$t" ]; then echo "$t"; return; fi
  echo "Engine JAR not found. Set \$INSPECTO_JAR or build it (mvn -o clean package)." >&2; exit 1
}
JAR="$(resolve_jar)"
DIR="$EXAMPLES_ROOT/$EX"
[ -d "$DIR" ] || { echo "No such example dir: $DIR" >&2; exit 1; }
PIPE="$(ls "$DIR"/*_pipeline.toon 2>/dev/null | head -1 || true)"
[ -n "$PIPE" ] || { echo "No *_pipeline.toon under $DIR (serve examples use the *_pipeline.toon convention)." >&2; exit 1; }

cd "$DIR"
[ "$CLEAN" = 1 ] && rm -rf out || true
mkdir -p out/inbox out/database out/backup out/temp out/errors out/quarantine out/markers out/status out/logs out/write
BASE="http://localhost:$PORT"
SRV_PID=""
cleanup(){ if [ -n "$SRV_PID" ] && kill -0 "$SRV_PID" 2>/dev/null; then kill "$SRV_PID" 2>/dev/null || true; echo "Server stopped."; fi; }
trap cleanup EXIT INT TERM

echo "Starting Inspecto serve mode on $BASE (poll ${POLL}s)"
echo "  jar:    $JAR"
echo "  config: $DIR"
java --enable-native-access=ALL-UNNAMED -Dcontrol.port="$PORT" -Dservice.poll.seconds="$POLL" \
     -Dassist.write.root=out/write -Djobs.audit.dir=out/jobs_audit -cp "$JAR" com.gamma.control.ControlApi . \
     >out/logs/serve.out.log 2>out/logs/serve.err.log &
SRV_PID=$!

up=0
for _ in $(seq 1 60); do
  sleep 0.5
  if ! kill -0 "$SRV_PID" 2>/dev/null; then echo "Server exited early:"; tail -n 30 out/logs/serve.err.log out/logs/serve.out.log 2>/dev/null || true; exit 1; fi
  if curl -fsS "$BASE/health" >/dev/null 2>&1; then up=1; break; fi
done
[ "$up" = 1 ] || { echo "Server did not become healthy on $BASE."; tail -n 30 out/logs/serve.err.log 2>/dev/null || true; exit 1; }

# Optional mtimes.txt: "<filename> <ISO-8601 or anything `touch -d` accepts>" per line (blank/# ignored).
# Applied to seeded inbox files after each drop, so examples can demonstrate mtime-sensitive features
# (incremental high-watermark, metadata dedup) deterministically — git does not preserve mtimes.
apply_mtimes(){
  [ -f mtimes.txt ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$(printf '%s' "$line" | tr -d '[:space:]')" in ''|\#*) continue;; esac
    f="$(printf '%s' "$line" | awk '{print $1}')"
    ts="$(printf '%s' "$line" | sed 's/^[^[:space:]]*[[:space:]]*//')"
    [ -e "out/inbox/$f" ] && touch -d "$ts" "out/inbox/$f" 2>/dev/null || true
  done < mtimes.txt
}

echo "Healthy. Seeding out/inbox/ from samples/ ..."
[ -d samples ] && cp -r samples/* out/inbox/ 2>/dev/null || true
apply_mtimes
W="$WAIT"; [ "$W" -gt 0 ] || W=$((POLL * 2 + 3))
echo "Waiting ${W}s for the poll loop to ingest..."; echo
sleep "$W"

# Optional second drop: re-present files (changed/duplicate content, same names) so examples can
# demonstrate the acquisition re-presentation family (checksum/metadata change, dedup, watermark),
# which is inherently a two-cycle scenario. Engaged only when the example ships a phase2/ dir.
if [ -d phase2 ]; then
  echo "Second drop: seeding out/inbox/ from phase2/ ..."
  cp -r phase2/* out/inbox/ 2>/dev/null || true
  apply_mtimes
  echo "Waiting ${W}s for the poll loop to process the second drop..."; echo
  sleep "$W"
fi

probe(){ echo "# GET $1"; curl -fsS "$BASE$1" 2>/dev/null || echo "  (request failed)"; echo; echo; }
probe "/pipelines"
probe "/events?limit=20"
if [ -f probes.txt ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    case "$(printf '%s' "$line" | tr -d '[:space:]')" in ''|\#*) continue;; esac
    probe "$(printf '%s' "$line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
  done < probes.txt
fi

if [ "$DEMO" = 1 ]; then
  echo "Demo complete; stopping server."
else
  echo "--- Server is running at $BASE ---"
  echo "  Explore:  curl $BASE/pipelines   |   curl \"$BASE/events?limit=20\""
  echo "  Drop more files into:  $(pwd)/out/inbox"
  echo "  Press Enter to stop."
  read -r _
fi
