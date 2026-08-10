#!/usr/bin/env bash
# Mede MB de RSS vazados por requisição /v1/embeddings do llama-server (bge-m3).
# Uso: ./measure-embed-leak.sh [segundos]  (default: 60s)
set -euo pipefail

DURATION="${1:-60}"
PID="$(pgrep -f 'bge-m3-Q8_0' | head -1)"
if [ -z "$PID" ]; then
  echo "llama-server (bge-m3) não está rodando" >&2
  exit 1
fi

rss_kb() { awk '/VmRSS/{print $2}' "/proc/$PID/status" 2>/dev/null || echo ""; }

start_rss=$(rss_kb)
start_reqs=$(journalctl --user -u llama-swap --since "-1s" --no-pager 2>/dev/null | grep -c 'POST /v1/embeddings' || true)
start_ts=$(date +%s)

echo "PID=$PID start_rss_kb=$start_rss"
sleep "$DURATION"

end_rss=$(rss_kb)
end_ts=$(date +%s)
if [ -z "$end_rss" ]; then
  echo "processo morreu durante a medição (provável OOM)" >&2
  exit 1
fi

elapsed=$((end_ts - start_ts))
reqs=$(journalctl --user -u llama-swap --since "-${elapsed}s" --no-pager 2>/dev/null | grep -c 'POST /v1/embeddings' || true)
delta_kb=$((end_rss - start_rss))
delta_mb=$((delta_kb / 1024))

echo "elapsed_s=$elapsed reqs=$reqs delta_rss_mb=$delta_mb"
if [ "$reqs" -gt 0 ]; then
  awk -v d="$delta_mb" -v r="$reqs" 'BEGIN { printf "mb_per_req=%.2f\n", d/r }'
else
  echo "mb_per_req=N/A (0 requisições no período)"
fi
