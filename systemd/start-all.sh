#!/usr/bin/env bash
# Dev launcher: start the three services in the foreground and kill them all on Ctrl-C.
# For anything that must survive a reboot or restart on crash, use the systemd units instead.
set -Eeuo pipefail

REPO="$HOME/Documentos/Repo"
LLAMA="$REPO/llama.cpp/build/bin/llama-server"

pids=()
cleanup() { kill "${pids[@]}" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

"$REPO/llama-swap/llama-swap" --config "$REPO/llm-proxy/llama-swap.yaml" --listen 127.0.0.1:8080 &
pids+=($!)

"$LLAMA" -m "$REPO/Models-llm/bge-m3-Q8_0.gguf" --embedding --pooling mean \
  -ngl 99 -c 32768 -t 4 -b 512 -ub 512 -np 4 --threads-http 4 \
  --host 127.0.0.1 --port 8082 &
pids+=($!)

# Proxy: prefer the built jar; fall back to spring-boot:run for iterative dev.
if [[ -f "$REPO/llm-proxy/target/llm-proxy-0.1.0.jar" ]]; then
  ( set -a; [[ -f "$REPO/llm-proxy/.env" ]] && . "$REPO/llm-proxy/.env"; set +a
    java -jar "$REPO/llm-proxy/target/llm-proxy-0.1.0.jar" ) &
else
  ( cd "$REPO/llm-proxy" && mvn -q spring-boot:run ) &
fi
pids+=($!)

echo "up: llama-swap :8080  llm-proxy :8090  embed :8082   (Ctrl-C to stop all)"
wait
