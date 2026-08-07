# Documentação central dos projetos

Existe um repositório central de documentação técnica em
`project-specs` (irmão deste repo), com a "fonte de verdade" de cada
projeto pessoal (arquitetura, glossário, convenções, decisões). Ele não tem
código, só docs.

Índice completo: `project-specs/README.md`.

## Projetos cobertos

| Projeto | Path do código | Docs |
|---|---|---|
| LazyInvest | `LazyInvest` | `project-specs/lazyinvest/` |
| LazyInvest-Insight | `LazyInvest-Insight` | `project-specs/lazyinvest-insight/` |
| lazy-invest-bff | `lazy-invest-bff` | `project-specs/lazyinvest-bff/` |
| cvm-ingestor | `cvm-ingestor` | `project-specs/cvm-ingestor/` |
| cvm-pdf-processor | `cvm-pdf-processor` | `project-specs/cvm-pdf-processor/` |
| cvm-financial-dataset | `cvm-financial-dataset` | `project-specs/cvm-financial-dataset/` |
| llm-proxy | `llm-proxy` | `project-specs/llm-proxy/` |

Cada pasta de docs tem 4 arquivos: `architecture.md` (stack, camadas, tabelas
do banco), `glossary.md` (termos de domínio), `conventions.md` (commit/branch/
nomenclatura), `decisions.md` (ADRs curtos + dívidas técnicas conhecidas).

## Como usar

- **Antes de qualquer tarefa neste repo**, leia os 3-4 arquivos de docs
  correspondentes em `project-specs/<projeto>/` — mesmo que a tarefa pareça
  simples, isso evita reinventar nomes de tabela, contrariar uma decisão já
  tomada, ou perder contexto de arquitetura.
- **Ao concluir uma tarefa** que mude estrutura de pastas/tabelas, introduza
  um termo novo de domínio, ou tome uma decisão técnica relevante, atualize
  o arquivo correspondente em `project-specs/<projeto>/`. Resuma o que foi
  atualizado (ou diga "nada a atualizar") antes de encerrar a sessão.
- **Implementar uma spec** (`project-specs/<projeto>/specs/SPEC-*.md`): ver
  `project-specs/harness/SPEC_IMPLEMENTATION_POLICY.md` — use o pipeline
  (`/implement`), não monte um fan-out de agentes manualmente.
- Nunca push automático — commit local, revisão manual, push manual.

## Novo projeto

Copiar `project-specs/_template/` para uma pasta nova (kebab-case) e
preencher os 4 arquivos; criar um `AGENTS.md` neste repo apontando pra lá
(ou rodar `project-specs/harness/sync.sh`, que gera esse arquivo).
