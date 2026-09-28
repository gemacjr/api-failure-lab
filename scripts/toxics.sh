#!/usr/bin/env bash
# Inject REAL network faults between the app and its dependencies via Toxiproxy's HTTP API.
# Proxies: "postgres" (app -> :15432 -> postgres) and "downstream" (app -> :18080 -> wiremock).
set -euo pipefail

API="${TOXIPROXY_API:-http://localhost:8474}"

usage() {
  cat <<'USAGE'
Usage: scripts/toxics.sh <command> [args]
  latency <proxy> <ms>   add latency to every response from <proxy>
  timeout <proxy>        stop all data; connections hang until the client gives up
  reset <proxy>          reset connections (TCP RST) immediately
  down <proxy>           disable the proxy (connection refused)
  up <proxy>             re-enable the proxy
  list                   show proxies and active toxics
  clear                  remove all toxics and re-enable all proxies
USAGE
}

add_toxic() { # proxy name type attributes-json
  curl -sf -X POST "$API/proxies/$1/toxics" -H 'Content-Type: application/json' \
    -d "{\"name\":\"$2\",\"type\":\"$3\",\"stream\":\"downstream\",\"attributes\":$4}"
  echo
}

case "${1:-}" in
  latency) add_toxic "$2" "latency_$2" latency "{\"latency\":$3,\"jitter\":0}" ;;
  timeout) add_toxic "$2" "timeout_$2" timeout '{"timeout":0}' ;;
  reset)   add_toxic "$2" "reset_$2" reset_peer '{"timeout":0}' ;;
  down)    curl -sf -X POST "$API/proxies/$2" -H 'Content-Type: application/json' -d '{"enabled":false}'; echo ;;
  up)      curl -sf -X POST "$API/proxies/$2" -H 'Content-Type: application/json' -d '{"enabled":true}'; echo ;;
  list)    curl -sf "$API/proxies"; echo ;;
  clear)   curl -sf -X POST "$API/reset"; echo "all toxics removed" ;;
  *)       usage; exit 1 ;;
esac
