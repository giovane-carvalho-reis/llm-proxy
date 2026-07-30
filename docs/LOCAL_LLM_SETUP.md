# Setup local do llama-swap (dependência de infra do llm-proxy)

O `llm-proxy` (container Docker) não roda nenhum LLM — ele só encaminha
`/v1/chat/completions` e embeddings para um `llama-swap` que roda **nativo no host**
(fora do Docker) na porta `8080`, alcançado pelo container via `host.docker.internal:8080`
(`LOCAL_LLAMA_URL`/`EMBED_URL` em `docker-compose.yml`).

Isso existe fora do Docker por um motivo: o `llama-swap`/`llama-server` precisam de acesso
direto à GPU (CUDA) da máquina, e passar isso para dentro de um container exigiria
`nvidia-container-toolkit` + rebuild da imagem a cada troca de driver — mais fricção do que
rodar nativo num único dev workstation.

Este documento registra o que foi investigado e corrigido em 2026-07-26 (o `llm-proxy`
estava recebendo `Connection refused` porque nada disso tinha sido instalado ainda) e como
reproduzir/automatizar em outra máquina.

## O que precisa estar instalado

| Dependência | Por quê | Onde/como |
|---|---|---|
| **Go ≥ 1.26** (ou Docker, se não quiser instalar Go) | Compilar o `llama-swap` (projeto Go, sem binário pré-buildado neste setup) | `go build` local, ou container `golang:1.23` com `GOTOOLCHAIN=auto` (baixa o 1.26 sozinho) — é o que o script faz |
| **git** | Clonar `github.com/mostlygeek/llama-swap` | já presente na maioria dos ambientes |
| **`llama-server` compilado com suporte a GPU** | É o processo que de fato carrega o `.gguf` e serve inferência; o `llama-swap` só sobe/derruba instâncias dele por request | Este repo usa um fork em `../llama-cpp-turboquant` (build próprio, com CUDA) — **não é compilado por este script**, é pré-requisito separado (ver `llama-cpp-turboquant/README.md`) |
| **Driver NVIDIA + `nvidia-smi`** | `-ngl 999` nos modelos exige camadas na GPU | `nvidia-smi` deve listar a GPU antes de tentar subir qualquer modelo |
| **Modelos `.gguf`** | Os arquivos que `llama-swap.yaml` referencia | `../Models-llm/*.gguf` (baixados manualmente, não versionados no git — são dezenas de GB) |
| **systemd de usuário** (`systemctl --user`) | Manter o `llama-swap` de pé entre logins/reboots, com restart automático | `~/.config/systemd/user/llama-swap.service` |

## O que foi corrigido (histórico do problema)

`llama-swap.yaml` e `systemd/llama-swap.service` foram escritos originalmente para uma
máquina com usuário `giovane` e `llama.cpp` buildado em `~/Documentos/Repo/llama.cpp`. Neste
ambiente o usuário real é `giovanehl2` e o binário que existe de fato é
`~/Documentos/repo/llama-cpp-turboquant/build/bin/llama-server` (repo minúsculo, fork
`turboquant`). Os dois arquivos foram corrigidos para os caminhos reais.

Também descobrimos que `llama-server` **não roda sem `LD_LIBRARY_PATH`** apontando para a
pasta do binário — `libllama-server-impl.so` fica ao lado dele, fora do `ld.so` padrão. Isso
foi resolvido com `Environment=LD_LIBRARY_PATH=...` no unit do systemd (ver
`systemd/llama-swap.service`).

**Isso é frágil por natureza**: os caminhos (`$HOME`, localização do fork do llama.cpp,
pasta dos modelos) são específicos desta máquina. Se migrar de workstation, os três pontos
acima (`llama-swap.yaml` macros, `LLAMA_SERVER_BIN` do script, `LD_LIBRARY_PATH` do service)
precisam ser revisados — não há isso resolvido pelo script.

## Setup automatizado

```bash
cd llm-proxy
./scripts/setup-llama-swap.sh              # clona+builda (se preciso) e ativa via systemd --user
./scripts/setup-llama-swap.sh --build-only # só garante o binário, não mexe em systemd
```

O script:
1. Confere `docker`, `git`, `nvidia-smi` e o `llama-server` já compilado (falha cedo com
   mensagem clara se `llama-server` não existir — não tenta compilar o llama.cpp sozinho).
2. Clona `mostlygeek/llama-swap` em `~/Documentos/Repo/llama-swap` se ainda não existir.
3. Compila via `docker run golang:1.23 ... go build` (não precisa de Go instalado no host;
   sem a tag `embed_ui`, então não builda a dashboard web — só a API/proxy que o `llm-proxy`
   usa).
4. Instala `systemd/llama-swap.service` em `~/.config/systemd/user/`, recarrega e ativa.
5. Faz polling em `/v1/models` até responder (ou falha com o comando de diagnóstico).

Idempotente: rodar de novo não reclona/recompila se o binário já existir.

## Verificação manual

```bash
# llama-swap de pé, modelos configurados:
curl -s http://127.0.0.1:8080/v1/models | jq

# alcançável a partir do container do llm-proxy:
docker exec repo-llm-proxy-1 curl -s http://host.docker.internal:8080/v1/models

# smoke test de inferência real (carrega o modelo na GPU, pode levar alguns segundos):
curl -s http://127.0.0.1:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen3-14b","messages":[{"role":"user","content":"diga apenas OK"}],"max_tokens":10}'

# status/logs do serviço:
systemctl --user status llama-swap.service
journalctl --user -u llama-swap -f
```

## Possíveis automações futuras (não implementadas)

- **Binário pré-compilado em release**: `mostlygeek/llama-swap` publica binários Linux
  prontos nas GitHub Releases — trocar o build via Docker por um `curl` do release fixaria
  a versão (hoje o script pega sempre o HEAD do repo) e eliminaria a dependência de
  Docker/Go só para isso.
- **Template de caminhos em vez de hardcode**: gerar `llama-swap.yaml` a partir de um
  `.yaml.tpl` com `envsubst` (`${HOME}`, `${LLAMA_SERVER_BIN}`) no próprio
  `setup-llama-swap.sh`, para não precisar editar o YAML à mão numa máquina nova.
- **Healthcheck no `llm-proxy`**: hoje o `llm-proxy` só descobre que o `llama-swap` está
  fora do ar quando uma requisição de chat falha (`Connection refused` nos logs). Um
  endpoint `/actuator/health` que também sonda `llama-cpp.base-url` daria sinal mais cedo
  (inclusive visível num dashboard de operação).
- **CI que valida os `.sql`/config antes de build** — fora do escopo deste documento, mas
  o mesmo tipo de "arquivo nunca testado contra o ambiente real" foi a causa de outro bug
  corrigido na mesma sessão (migration do cvm-pdf-processor desalinhada com o schema
  atual); vale considerar um smoke test de boot em CI para os serviços que sobem schema
  próprio.
