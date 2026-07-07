#!/usr/bin/env bash
# Dev launcher: start llama-swap + proxy in the foreground and kill both on Ctrl-C.
# Embeddings (bge-m3) agora são um modelo do llama-swap — não há terceiro processo.
# For anything that must survive a reboot or restart on crash, use the systemd units instead.
set -Eeuo pipefail

REPO="$HOME/Documentos/Repo"

pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

"$REPO/llama-swap/llama-swap" --config "$REPO/llm-proxy/llama-swap.yaml" --listen 127.0.0.1:8080 &
pids+=($!)

# Proxy: prefer the built jar; fall back to spring-boot:run for iterative dev.
if [[ -f "$REPO/llm-proxy/target/llm-proxy-0.1.0.jar" ]]; then
  ( set -a; [[ -f "$REPO/llm-proxy/.env" ]] && . "$REPO/llm-proxy/.env"; set +a
    java -jar "$REPO/llm-proxy/target/llm-proxy-0.1.0.jar" ) &
else
  ( cd "$REPO/llm-proxy" && mvn -q spring-boot:run ) &
fi
pids+=($!)

echo "up: llama-swap :8080 (chat + bge-m3)  llm-proxy :8091   (Ctrl-C to stop all)"
wait
