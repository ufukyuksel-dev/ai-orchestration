#!/usr/bin/env bash
# AI Orchestration: one-command install for Claude Code, Codex and GitHub Copilot.
#
#   ./install.sh                 install or upgrade (safe to re-run)
#   ./install.sh --uninstall     remove the service, agent registrations and instruction blocks; data is kept
#   ./install.sh --purge-data    also delete the database and vector volumes (asks first)
#   ./install.sh --no-agents     do not touch Claude/Codex/Copilot settings
#   ./install.sh --with-local-llm  also pull the optional local model (qwen3:8b)
#   ./install.sh --foreground-service  run the server as a plain background process (CI, no launchd/systemd)
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT="${AI_ORCH_PORT:-18080}"
BASE="http://127.0.0.1:${PORT}"
DATA="${XDG_DATA_HOME:-$HOME/.local/share}/ai-orch"
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/ai-orch"
BIN="$HOME/.local/bin"
JAR="$DATA/ai-orchestration.jar"
STATE_FILE="$DATA/state.json"
LOG="$STATE_DIR/server.log"
# A second, fully separate instance (tests, trying an upgrade) only needs AI_ORCH_INSTANCE and other ports.
INSTANCE="${AI_ORCH_INSTANCE:-local}"
PG_PORT="${AI_ORCH_PG_PORT:-55432}"
QDRANT_HTTP_PORT="${AI_ORCH_QDRANT_HTTP_PORT:-6333}"
QDRANT_GRPC_PORT="${AI_ORCH_QDRANT_GRPC_PORT:-6334}"
export AI_ORCH_INSTANCE="$INSTANCE" AI_ORCH_PG_PORT="$PG_PORT" AI_ORCH_QDRANT_HTTP_PORT="$QDRANT_HTTP_PORT" \
       AI_ORCH_QDRANT_GRPC_PORT="$QDRANT_GRPC_PORT" AI_ORCH_URL="$BASE" AI_ORCH_QDRANT_URL="http://127.0.0.1:$QDRANT_HTTP_PORT"
COMPOSE=(docker compose -p "ai-orch-$INSTANCE" -f "$REPO/deploy/docker-compose.local.yml")
[ "$INSTANCE" = local ] && COMPOSE=(docker compose -f "$REPO/deploy/docker-compose.local.yml")
PG_CONTAINER="ai-orch-$INSTANCE-postgres"
LABEL="com.mbworldwideapps.ai-orch$([ "$INSTANCE" = local ] || echo ".$INSTANCE")"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
UNIT_NAME="ai-orch$([ "$INSTANCE" = local ] || echo "-$INSTANCE").service"
UNIT="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/$UNIT_NAME"
CODEX_CONFIG="${CODEX_HOME:-$HOME/.codex}/config.toml"
# long-form references: keep an existing ~/Projects/ai-references, otherwise live next to the data
REF_ROOT="${AI_REFERENCE_ROOT:-$([ -d "$HOME/Projects/ai-references" ] && echo "$HOME/Projects/ai-references" || echo "${XDG_DATA_HOME:-$HOME/.local/share}/ai-orch/references")}"

MODE=install; AGENTS=1; LOCAL_LLM=0; FOREGROUND=0
for arg in "$@"; do
  case "$arg" in
    --uninstall) MODE=uninstall ;;
    --purge-data) MODE=purge ;;
    --no-agents) AGENTS=0 ;;
    --with-local-llm) LOCAL_LLM=1 ;;
    --foreground-service) FOREGROUND=1 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg (see --help)" >&2; exit 2 ;;
  esac
done

step() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }
die()  { printf '  \033[31m✗\033[0m %s\n' "$1" >&2; [ -n "${2:-}" ] && printf '    → %s\n' "$2" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }
os() { case "$(uname -s)" in Darwin) echo mac ;; Linux) echo linux ;; *) echo other ;; esac; }

# ------------------------------------------------------------------ service
stop_service() {
  if [ "$(os)" = mac ] && [ -f "$PLIST" ]; then
    launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  elif [ "$(os)" = linux ] && [ -f "$UNIT" ] && have systemctl; then
    systemctl --user stop "$UNIT_NAME" 2>/dev/null || true
  fi
  if [ -f "$STATE_DIR/server.pid" ]; then
    kill "$(cat "$STATE_DIR/server.pid")" 2>/dev/null || true
    rm -f "$STATE_DIR/server.pid"
  fi
  # stopping is asynchronous: wait until the job is unloaded and the port is free
  for _ in $(seq 1 60); do
    { [ "$(os)" = mac ] && launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1; } && { sleep 1; continue; }
    curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && { sleep 1; continue; }
    return 0
  done
}

server_args() {
  printf '%s\n' "--server.port=$PORT" "--ai-orchestration.references.root-path=$REF_ROOT" \
    "--spring.datasource.url=jdbc:postgresql://localhost:$PG_PORT/ai_orchestration" \
    "--spring.ai.vectorstore.qdrant.port=$QDRANT_GRPC_PORT" \
    "--ai-orchestration.agent.prefs-file=$DATA/prefs.json"
}

write_service() { # $1 = extra environment line (KEY=VALUE) or empty
  local java; java="$(command -v java)"
  local extra="${1:-}"
  if [ "$FOREGROUND" = 1 ]; then
    local args=(); while IFS= read -r a; do args+=("$a"); done < <(server_args)
    ( env ${extra:+"$extra"} nohup "$java" -jar "$JAR" "${args[@]}" >>"$LOG" 2>&1 & echo $! >"$STATE_DIR/server.pid" )
    return
  fi
  case "$(os)" in
    mac)
      mkdir -p "$(dirname "$PLIST")"
      local env_xml=""
      [ -n "$extra" ] && env_xml="<key>EnvironmentVariables</key><dict><key>${extra%%=*}</key><string>${extra#*=}</string></dict>"
      cat >"$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>$java</string><string>-jar</string><string>$JAR</string>$(server_args | sed 's|.*|<string>&</string>|' | tr -d '\n')</array>
  $env_xml
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$LOG</string>
  <key>StandardErrorPath</key><string>$LOG</string>
</dict></plist>
EOF
      launchctl bootstrap "gui/$(id -u)" "$PLIST" ;;
    linux)
      mkdir -p "$(dirname "$UNIT")"
      cat >"$UNIT" <<EOF
[Unit]
Description=AI Orchestration (rules and memory for coding agents)
After=network-online.target

[Service]
ExecStart=$java -jar $JAR $(server_args | tr '\n' ' ')
${extra:+Environment=$extra}
Restart=on-failure
StandardOutput=append:$LOG
StandardError=append:$LOG

[Install]
WantedBy=default.target
EOF
      systemctl --user daemon-reload
      systemctl --user enable --now "$UNIT_NAME" ;;
    *) die "unsupported OS: $(uname -s)" "use --foreground-service" ;;
  esac
}

wait_healthy() {
  for _ in $(seq 1 90); do
    curl -sf "$BASE/actuator/health" >/dev/null 2>&1 && return 0
    sleep 1
  done
  die "the server did not become healthy in 90 s" "see $LOG"
}

# ------------------------------------------------------------------ agents
MCP_URL="$BASE/mcp"

claude_always_loads() {
  python3 -c 'import json,os,sys; s=json.load(open(os.path.expanduser("~/.claude.json"))).get("mcpServers",{}).get("ai-orchestration",{}); sys.exit(0 if s.get("alwaysLoad") is True else 1)' 2>/dev/null
}

register_claude() {
  have claude || { warn "Claude Code not found (skipped)"; return; }
  local current; current="$(claude mcp get ai-orchestration 2>/dev/null || true)"
  if [ -n "$current" ]; then
    if printf '%s' "$current" | grep -q "$MCP_URL" && printf '%s' "$current" | grep -q "X-AI-Orch-Tools: minimal" \
        && claude_always_loads; then
      ok "Claude Code already registered"
    elif printf '%s' "$current" | grep -Eq 'https?://(127\.0\.0\.1|localhost)'; then
      claude mcp remove --scope user ai-orchestration >/dev/null 2>&1 || claude mcp remove ai-orchestration >/dev/null 2>&1 || true
      current=""
    else
      die "a different 'ai-orchestration' MCP server is registered in Claude Code; not touching it" \
          "claude mcp remove ai-orchestration   (then re-run ./install.sh)"
    fi
  fi
  if [ -z "$current" ]; then
    # minimal tool profile: the session start comes from the prompt hook below, so only memory.learn,
    # rules.instructions and extras travel with every turn. alwaysLoad keeps these three out of Claude Code's
    # deferred tool search (other MCP servers stay deferred), so the agent sees them without searching first.
    claude mcp add-json --scope user ai-orchestration "{\"type\":\"http\",\"url\":\"$MCP_URL\",\"alwaysLoad\":true,\"headers\":{\"X-AI-Orch-Client\":\"claude-code\",\"X-AI-Orch-Tools\":\"minimal\"}}" >/dev/null
    ok "Claude Code registered ($MCP_URL)"
  fi
  if python3 "$REPO/scripts/claude_hook_config.py" set "$HOME/.claude/settings.json" \
      "AI_ORCH_URL=$BASE python3 $REPO/scripts/hooks/ai_orch_prompt_context.py" \
      "python3 $REPO/scripts/hooks/ai_orch_learn_reminder.py"
  then ok "Claude Code hooks installed (memory and rules arrive with each request; learning is asked for at task end)"
  else warn "Claude Code hooks not installed: ~/.claude/settings.json is not valid JSON"
  fi
}

register_codex() {
  have codex || { warn "Codex not found (skipped)"; return; }
  if python3 "$REPO/scripts/codex_mcp_config.py" set "$CODEX_CONFIG" "$MCP_URL"
  then ok "Codex registered ($CODEX_CONFIG)"
  else die "a different 'ai-orchestration' server is configured in $CODEX_CONFIG; not touching it" "remove that table, then re-run ./install.sh"
  fi
}

unregister_agents() {
  if have claude && claude mcp get ai-orchestration 2>/dev/null | grep -q "$MCP_URL"; then
    claude mcp remove --scope user ai-orchestration >/dev/null 2>&1 || claude mcp remove ai-orchestration >/dev/null 2>&1 || true
    ok "Claude Code registration removed"
  fi
  if [ -f "$HOME/.claude/settings.json" ] && grep -q "ai_orch_" "$HOME/.claude/settings.json"; then
    python3 "$REPO/scripts/claude_hook_config.py" remove "$HOME/.claude/settings.json" && ok "Claude Code hooks removed"
  fi
  if [ -f "$CODEX_CONFIG" ] && grep -q "# managed by AI Orchestration install.sh" "$CODEX_CONFIG"; then
    python3 "$REPO/scripts/codex_mcp_config.py" remove "$CODEX_CONFIG"
    ok "Codex registration removed"
  fi
  if have claude || have codex; then
    python3 "$REPO/scripts/install_agent_instructions.py" --uninstall >/dev/null && ok "session instruction blocks removed"
  fi
}

install_shim() {
  mkdir -p "$BIN"
  # an older install linked ai_orch straight to the bridge script: replace the link, never write through it
  rm -f "$BIN/ai_orch"
  cat >"$BIN/ai_orch" <<EOF
#!/usr/bin/env bash
# AI Orchestration command (installed by $REPO/install.sh)
export AI_ORCH_URL="\${AI_ORCH_URL:-$BASE}" AI_ORCH_QDRANT_URL="\${AI_ORCH_QDRANT_URL:-http://127.0.0.1:$QDRANT_HTTP_PORT}" AI_ORCH_STATE="$STATE_FILE"
case "\${1:-}" in
  doctor) exec python3 "$REPO/scripts/ai_orch_admin.py" "\$@" ;;
  project) [ "\${2:-}" = add ] && exec python3 "$REPO/scripts/ai_orch_admin.py" "\$@" ;;
esac
exec python3 "$REPO/skills/copilot/ai-orchestration-memory/scripts/ai_orch.py" "\$@"
EOF
  chmod +x "$BIN/ai_orch"
  ok "ai_orch command installed ($BIN/ai_orch)"
  case ":$PATH:" in *":$BIN:"*) ;; *) warn "$BIN is not on your PATH; add: export PATH=\"$BIN:\$PATH\"" ;; esac
}

memory_count() {
  docker exec "$PG_CONTAINER" psql -U ai_orch -d ai_orchestration -qAtc \
    "SELECT count(*) FROM memory_items" 2>/dev/null || echo 0
}

# ------------------------------------------------------------------ uninstall / purge
if [ "$MODE" != install ]; then
  step "Stopping the service"
  stop_service; rm -f "$PLIST" "$UNIT"; have systemctl && systemctl --user daemon-reload 2>/dev/null || true
  ok "service removed"
  step "Agents"
  unregister_agents; rm -f "$BIN/ai_orch"; ok "ai_orch command removed"
  if [ "$MODE" = purge ]; then
    read -r -p "Delete ALL AI Orchestration memories, rules and indexes? Type 'delete' to confirm: " answer
    [ "$answer" = delete ] || die "not confirmed; data kept"
    "${COMPOSE[@]}" down -v; rm -rf "$DATA"; ok "data deleted"
  else
    ok "data kept (database and vector volumes, $DATA)"
  fi
  exit 0
fi

# ------------------------------------------------------------------ install
step "Checking prerequisites"
have docker && docker info >/dev/null 2>&1 || die "Docker is not running" "start Docker Desktop (or: sudo systemctl start docker)"
ok "Docker"
have ollama || die "Ollama is not installed" "https://ollama.com/download"
curl -sf http://127.0.0.1:11434/api/tags >/dev/null || die "Ollama is not running" "ollama serve   (or open the Ollama app)"
ok "Ollama"
have java || die "Java 21+ is not installed" "$([ "$(os)" = mac ] && echo 'brew install openjdk@21' || echo 'sudo apt install openjdk-21-jdk')"
java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
[ "${java_major:-0}" -ge 21 ] || die "Java $java_major found, 21+ needed" "$([ "$(os)" = mac ] && echo 'brew install openjdk@21' || echo 'sudo apt install openjdk-21-jdk')"
ok "Java $java_major"
have python3 || die "python3 is required" "install Python 3"
ok "Python 3"

step "Starting the database and vector store"
"${COMPOSE[@]}" up -d --wait >/dev/null
ok "Postgres and Qdrant are up"

step "Embedding model"
if ollama list 2>/dev/null | awk '{print $1}' | grep -q '^bge-m3'; then ok "bge-m3 already present"; else ollama pull bge-m3; ok "bge-m3 pulled"; fi
if [ "$LOCAL_LLM" = 1 ]; then ollama pull qwen3:8b; ok "qwen3:8b pulled (optional local model)"; fi

step "Building the server"
(cd "$REPO" && ./mvnw -q -DskipTests package)
mkdir -p "$DATA" "$STATE_DIR" "$REF_ROOT"
cp "$(ls "$REPO"/target/ai-orchestration-*.jar | grep -v original | head -1)" "$JAR.new"

step "Installing the background service"
existing="$(memory_count)"
backfill=""
if [ "${existing:-0}" -gt 0 ] && ! grep -q '"embeddingModel": *"bge-m3"' "$STATE_FILE" 2>/dev/null; then
  mkdir -p "$DATA/backups"
  backup="$DATA/backups/ai_orchestration-$(date +%Y%m%d-%H%M%S).dump"
  docker exec "$PG_CONTAINER" pg_dump -U ai_orch -Fc ai_orchestration >"$backup"
  ok "upgrade: $existing memories backed up to $backup"
  backfill="AI_ORCH_MEMORY_BACKFILL_ON_STARTUP=true"
fi
stop_service
mv "$JAR.new" "$JAR"
if curl -sf "$BASE/actuator/health" >/dev/null 2>&1; then
  die "port $PORT is used by another AI Orchestration process" "stop it (e.g. the IDE run configuration), then re-run ./install.sh"
fi
: >"$LOG"
write_service "$backfill"
wait_healthy
ok "server running at $BASE"
embedded=1
if [ -n "$backfill" ]; then
  for _ in $(seq 1 600); do grep -q "memory vector backfill complete" "$LOG" && break; sleep 1; done
  result="$(grep "memory vector backfill complete" "$LOG" | tail -1)"
  [ -n "$result" ] || die "memory re-embedding did not finish" "see $LOG"
  if printf '%s' "$result" | grep -q "failed=0,"; then
    ok "memories re-embedded with bge-m3 ($(printf '%s' "$result" | grep -o 'upserted=[0-9]*'))"
    [ "$FOREGROUND" = 1 ] || write_service_later=1
  else
    # keep the backfill flag: every restart (and the next ./install.sh) retries the failed ones
    embedded=0
    warn "some memories could not be re-embedded ($(printf '%s' "$result" | grep -o 'failed=[0-9]*')); they are retried on the next start — see $LOG"
  fi
fi
if [ "$embedded" = 1 ]; then
  printf '{"embeddingModel": "bge-m3", "agents": %s}\n' "$([ "$AGENTS" = 1 ] && echo true || echo false)" >"$STATE_FILE"
else
  printf '{"embeddingModel": "pending", "agents": %s}\n' "$([ "$AGENTS" = 1 ] && echo true || echo false)" >"$STATE_FILE"
fi
if [ "${write_service_later:-0}" = 1 ]; then
  # the one-time backfill flag must not survive the next restart: rewrite the unit without it
  stop_service; write_service ""; wait_healthy
fi

install_shim

if [ "$AGENTS" = 1 ]; then
  step "Registering agents"
  register_claude
  register_codex
  if have claude || have codex; then
    python3 "$REPO/scripts/install_agent_instructions.py" >/dev/null && ok "session instructions and skills installed"
  fi
  if have copilot || [ -d "$HOME/.copilot" ]; then
    ok "Copilot: per repository, run  ai_orch project add <folder> --copilot"
  fi
fi

step "Checking the installation"
"$BIN/ai_orch" doctor || die "the installation is not healthy yet" "fix the ✗ lines above, then re-run ./install.sh"
[ "$embedded" = 1 ] || die "memories are not fully re-embedded yet" "see $LOG, then re-run ./install.sh"

printf '\n\033[1mReady.\033[0m Open the panel: %s/  →  "Proje ekle" to index your first repository.\n' "$BASE"
