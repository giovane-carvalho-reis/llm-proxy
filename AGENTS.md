<!-- GERADO por project-specs/harness/render.py — não editar -->

# llm-proxy — instrução base

Documentação central em `project-specs/llm-proxy/` (hotspots, codemap, architecture, glossary,
conventions, decisions). Specs ativas em `project-specs/llm-proxy/specs/`.

## Este repo

- **Stack:** java
- **Propósito:** Gateway OpenAI-compatible → llama.cpp local / OpenRouter
- **Teste unit:** `mvn -q test`
- **Integração:** `mvn -q verify`

## Antes de qualquer tarefa

1. Leia `project-specs/llm-proxy/hotspots.md`.
2. Path desconhecido → agente `scout-llm-proxy` (codemap embutido).
3. Testes → `test-runner`. Dados reais → `db-inspector`.
4. Diff grande → `revisor-java`.
5. Fechamento com mudança estrutural → skill `spec-sync`.

## Regras

- Nunca push automático; commit local só se pedido.
- Não editar este arquivo à mão — regenerar com `project-specs/harness/render.py --apply`.
- Mapa completo do workspace: `../AGENTS.md` (raiz `~/Documents/Repo`).
