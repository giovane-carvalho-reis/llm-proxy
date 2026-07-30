#!/usr/bin/env bash
# Prepara o llama-swap (proxy local de modelos de chat/embeddings, :8080) que o llm-proxy
# (container, LOCAL_LLAMA_URL/EMBED_URL) consome via host.docker.internal:8080.
#
# Faz: clona+compila o llama-swap (Go) via container docker (sem exigir Go instalado no
# host), confere os pré-requisitos que este script NÃO resolve sozinho (llama-server já
# compilado, GPU visível, modelos .gguf presentes), e instala o serviço systemd --user.
#
# Uso:
#   ./setup-llama-swap.sh            # clona/compila se preciso + instala e ativa o serviço
#   ./setup-llama-swap.sh --build-only   # só garante o binário, não mexe em systemd
#
# Ver docs/LOCAL_LLM_SETUP.md para o que cada dependência faz e por que é assim.

set -Eeuo pipefail

LLAMA_SWAP_DIR="${LLAMA_SWAP_DIR:-$HOME/Documentos/Repo/llama-swap}"
LLAMA_SWAP_REPO="${LLAMA_SWAP_REPO:-https://github.com/mostlygeek/llama-swap.git}"
GOLANG_IMAGE="${GOLANG_IMAGE:-golang:1.23}"
LLAMA_SERVER_BIN="${LLAMA_SERVER_BIN:-$HOME/Documentos/repo/llama-cpp-turboquant/build/bin/llama-server}"
MODELS_DIR="${MODELS_DIR:-$HOME/Documentos/repo/Models-llm}"
LLM_PROXY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_ONLY=false
[[ "${1:-}" == "--build-only" ]] && BUILD_ONLY=true

log() { echo "[setup-llama-swap] $*" >&2; }
fail() { echo "[setup-llama-swap] ERRO: $*" >&2; exit 1; }

# --- pré-requisitos que este script não resolve sozinho -----------------------------------
command -v docker >/dev/null || fail "docker não encontrado (necessário só para compilar o llama-swap)."
command -v git    >/dev/null || fail "git não encontrado."

[[ -x "$LLAMA_SERVER_BIN" ]] || fail "llama-server não encontrado em $LLAMA_SERVER_BIN.
Este script NÃO compila o llama.cpp/turboquant (build com CUDA é grande e específico da
GPU) — construa-o separadamente (ver llama-cpp-turboquant/README.md) e re-rode."

if command -v nvidia-smi >/dev/null; then
    nvidia-smi --query-gpu=name,memory.used,memory.total --format=csv,noheader | sed 's/^/[setup-llama-swap] GPU: /' >&2
else
    log "aviso: nvidia-smi não encontrado — llama-server pode falhar ao alocar camadas na GPU (-ngl 999)."
fi

[[ -d "$MODELS_DIR" ]] || fail "diretório de modelos não encontrado: $MODELS_DIR (ajuste MODELS_DIR ou baixe os .gguf)."
for f in qwen3-14b.gguf bge-m3-Q8_0.gguf Qwen3.6-35B-A3B-Q4_K_M.gguf; do
    [[ -f "$MODELS_DIR/$f" ]] || log "aviso: modelo esperado ausente: $MODELS_DIR/$f (só falha se llama-swap.yaml referenciar esse arquivo)."
done

# --- clona + compila o llama-swap ----------------------------------------------------------
if [[ ! -x "$LLAMA_SWAP_DIR/llama-swap" ]]; then
    if [[ ! -d "$LLAMA_SWAP_DIR/.git" ]]; then
        log "clonando llama-swap em $LLAMA_SWAP_DIR"
        git clone --depth 1 "$LLAMA_SWAP_REPO" "$LLAMA_SWAP_DIR"
    fi

    log "compilando llama-swap via docker ($GOLANG_IMAGE) — sem tag embed_ui (dashboard web não é necessário aqui)"
    docker run --rm -v "$LLAMA_SWAP_DIR:/src" -w /src -e GOTOOLCHAIN=auto "$GOLANG_IMAGE" sh -c \
        "git config --global --add safe.directory /src && go build -o build/llama-swap ."

    cp "$LLAMA_SWAP_DIR/build/llama-swap" "$LLAMA_SWAP_DIR/llama-swap"
    chmod +x "$LLAMA_SWAP_DIR/llama-swap"
else
    log "binário já existe em $LLAMA_SWAP_DIR/llama-swap — pulando build (apague o diretório para recompilar)."
fi

"$LLAMA_SWAP_DIR/llama-swap" --version >&2

$BUILD_ONLY && { log "build-only: pulando systemd."; exit 0; }

# --- instala o serviço systemd --user -------------------------------------------------------
command -v systemctl >/dev/null || fail "systemctl não encontrado — instale/ative manualmente."

# Mata qualquer llama-swap solto por fora do systemd (ex.: nohup manual de uma sessão
# anterior) — senão ele fica segurando a porta 8080 e o serviço novo falha ao subir.
EXISTING_PID="$(pgrep -f "$LLAMA_SWAP_DIR/llama-swap" || true)"
if [[ -n "$EXISTING_PID" ]]; then
    log "encerrando llama-swap solto (PID $EXISTING_PID) antes de subir via systemd"
    kill "$EXISTING_PID"
    sleep 1
fi

mkdir -p "$HOME/.config/systemd/user"
cp "$LLM_PROXY_DIR/systemd/llama-swap.service" "$HOME/.config/systemd/user/llama-swap.service"
systemctl --user daemon-reload
systemctl --user enable --now llama-swap.service

log "aguardando health check..."
for _ in $(seq 1 10); do
    curl -sf http://127.0.0.1:8080/v1/models >/dev/null 2>&1 && { log "OK: llama-swap respondendo em :8080"; exit 0; }
    sleep 1
done
fail "llama-swap não respondeu em :8080 — ver: systemctl --user status llama-swap.service ; journalctl --user -u llama-swap -e"
