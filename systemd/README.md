# Running the LLM stack

Two long-running services:

| Service            | Port | What                                                                   |
|--------------------|------|------------------------------------------------------------------------|
| `llama-swap`       | 8080 | On-demand `llama-server` supervisor (qwen3-14b ↔ qwen3-35b + bge-m3)  |
| `llm-proxy`        | 8091 | OpenAI-compat gateway; chat (llama-cpp vs openrouter) + embeddings     |

Chain: `cvm / LazyInvest → llm-proxy(:8091) → llama-swap(:8080) → llama-server`.
Embeddings (bge-m3) são um modelo do llama-swap em grupo próprio (CPU, sem swap).

## Production: systemd (recommended)

```bash
# build the proxy jar once
cd ~/Documentos/Repo/llm-proxy && mvn -q package -DskipTests

# install the user units
mkdir -p ~/.config/systemd/user
cp systemd/*.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now llama-swap llm-proxy

# start on boot without an active login session
loginctl enable-linger "$USER"

# logs
journalctl --user -u llm-proxy -f
```

Optional env for the proxy — put in `~/Documentos/Repo/llm-proxy/.env`:

```
OPENROUTER_API_KEY=sk-or-...
# LLM_SPEED_TOKEN_THRESHOLD=8000
```

Point cvm-pdf-processor at the proxy: `OLLAMA_BASE_URL=http://localhost:8091`.

## Dev: one script (no boot-start, no auto-restart)

```bash
./systemd/start-all.sh      # Ctrl-C stops both
```
