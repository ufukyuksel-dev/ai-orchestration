#!/usr/bin/env bash
# Start/stop the isolated benchmark backend.  Usage: bench/server.sh start|stop|reset
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$root/bench/.work"; mkdir -p "$work"
port="${AI_ORCH_BENCH_PORT:-18200}"
pg="${AI_ORCH_PG_CONTAINER:-ai-orch-local-postgres}"
psql() { docker exec "$pg" psql -U ai_orch -d postgres -qAt "$@"; }
case "${1:-start}" in
  reset)
    "$0" stop || true
    psql -c "DROP DATABASE IF EXISTS ai_orch_bench" >/dev/null
    for c in bench_memory_episodic_bge_m3_1024 bench_code_baseline_bge_m3_1024; do
      curl -s -X DELETE "http://localhost:6333/collections/$c" >/dev/null || true
    done
    rm -rf "$work/references"
    echo "bench state reset" ;;
  start)
    if curl -sf "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1; then echo "bench backend already up on $port"; exit 0; fi
    jar="${BENCH_JAR:-$(ls "$root"/target/ai-orchestration-*.jar 2>/dev/null | grep -v original | head -1 || true)}"
    if [ -z "$jar" ]; then (cd "$root" && mvn -q package -DskipTests); jar="$(ls "$root"/target/ai-orchestration-*.jar | grep -v original | head -1)"; fi
    [ "$(psql -c "SELECT 1 FROM pg_database WHERE datname='ai_orch_bench'")" = "1" ] || psql -c "CREATE DATABASE ai_orch_bench" >/dev/null
    mkdir -p "$work/references"
    (cd "$root" && nohup java -jar "$jar" --spring.config.additional-location="file:$root/bench/application-bench.yml" \
        > "$work/server-$port.log" 2>&1 & echo $! > "$work/server-$port.pid")
    for _ in $(seq 1 90); do curl -sf "http://127.0.0.1:$port/actuator/health" >/dev/null 2>&1 && { echo "bench backend up on $port"; exit 0; }; sleep 1; done
    echo "bench backend failed to start; see $work/server-$port.log" >&2; exit 1 ;;
  stop)
    [ -f "$work/server-$port.pid" ] && kill "$(cat "$work/server-$port.pid")" 2>/dev/null || true
    rm -f "$work/server-$port.pid"; echo "bench backend on $port stopped" ;;
esac
