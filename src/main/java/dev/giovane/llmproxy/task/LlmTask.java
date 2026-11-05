package dev.giovane.llmproxy.task;

import dev.giovane.llmproxy.api.InvalidRequestException;

/**
 * Tarefa declarada pelo chamador no header {@code X-Llm-Task}. Cada tarefa fixa parâmetros de geração no proxy
 * (SPEC-resumo-ipe-dedicado-e-consolidacao-consultor, Design 3); sem header o payload segue intacto.
 */
public enum LlmTask {

    /** Resumo de documento IPE: temperature 0 e raciocínio desligado, ignorando o que o chamador mandou. */
    IPE_SUMMARY("ipe-summary");

    public static final String HEADER = "X-Llm-Task";
    public static final String UNKNOWN_CODE = "TAREFA_DESCONHECIDA";

    private final String wireName;

    LlmTask(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Header ausente ou em branco = sem tarefa (null); valor desconhecido = 400 {@code TAREFA_DESCONHECIDA}. */
    public static LlmTask parse(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.strip();
        for (LlmTask task : values()) {
            if (task.wireName.equalsIgnoreCase(value)) {
                return task;
            }
        }
        throw new InvalidRequestException("unknown " + HEADER + " value: " + value, UNKNOWN_CODE);
    }
}
