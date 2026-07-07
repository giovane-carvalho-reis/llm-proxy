# Running the LLM stack

Three long-running services:

| Service            | Port | What                                                        |
|--------------------|------|-------------------------------------------------------------|
| `llama-swap`       | 8080 | On-demand `llama-server` supervisor (qwen3-14b ↔ qwen3-35b) |
| `llm-proxy`        | 8090 | OpenAI-compat gateway; routes llama-cpp vs openrouter       |
| `llm-embed`        | 8082 | bge-m3 embedding server, always-on                          |

Chain: `cvm → llm-proxy(:8090) → llama-swap(:8080) → llama-server`.

## Production: systemd (recommended)

```bash
# build the proxy jar once
cd ~/Documentos/Repo/llm-proxy && mvn -q package -DskipTests

# install the user units
mkdir -p ~/.config/systemd/user
cp systemd/*.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now llama-swap llm-proxy llm-embed

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

Point cvm-pdf-processor at the proxy: `OLLAMA_BASE_URL=http://localhost:8090`.

## Dev: one script (no boot-start, no auto-restart)

```bash
./systemd/start-all.sh      # Ctrl-C stops all three
```
