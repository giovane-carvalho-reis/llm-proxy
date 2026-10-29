package dev.giovane.llmproxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.giovane.llmproxy.config.ProxyProperties;
import dev.giovane.llmproxy.router.Router;

import org.springframework.stereotype.Component;

/** Request-body mutations per backend: forced temperature and the OpenRouter-only provider/usage/cache fields. */
@Component
class OpenRouterPayloadCustomizer {

    private final ProxyProperties props;
    private final ObjectMapper mapper;

    OpenRouterPayloadCustomizer(ProxyProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    void customize(ObjectNode json, String target, ProxyProperties.Upstream up) {
        boolean openrouter = Router.OPENROUTER.equals(target);
        if (openrouter && props.rejectsTemperature(json.path("model").asText(null))) {
            json.remove("temperature"); // nem a do chamador: com require_parameters, zeraria os endpoints
        } else if (up.temperature() != null) {
            json.put("temperature", up.temperature());
        }
        if (openrouter) {
            pinOpenRouterProvider(json);
            if (up.usageAccountingEnabled()) {
                injectUsageAccounting(json);
            }
            if (up.promptCacheEnabled()) {
                injectPromptCache(json);
            }
            if (up.reasoningDefaultOffEnabled()) {
                injectReasoningOff(json);
            }
        }
    }

    /**
     * Tool calling e JSON Schema precisam de suporte real do endpoint; nesses casos fixamos
     * a compatibilidade para não deixar o OpenRouter ignorar parâmetros silenciosamente.
     * Em geração textual comum a trava é contraproducente: parâmetros opcionais (reasoning,
     * temperature) variam entre endpoints do mesmo modelo e podem zerar os candidatos com 404.
     */
    private void pinOpenRouterProvider(ObjectNode json) {
        boolean hasTools = json.path("tools").isArray() && !json.path("tools").isEmpty();
        boolean hasJsonSchema = "json_schema".equals(json.path("response_format").path("type").asText());
        if (!json.has("provider") && (hasTools || hasJsonSchema)) {
            ObjectNode provider = mapper.createObjectNode();
            provider.put("allow_fallbacks", false);
            provider.put("require_parameters", true);
            json.set("provider", provider);
        }
    }

    /**
     * ADR-019 (incidente 2026-10-13: {@code content: null} com max_tokens baixo): raciocínio desligado por padrão.
     * Nunca sobrescreve o chamador e não injeta com {@code provider.require_parameters=true} (tools/json_schema ou
     * provider do chamador): {@code reasoning} não é suportado por todo endpoint e o filtro zeraria os candidatos (404).
     * Deve rodar depois de {@link #pinOpenRouterProvider}.
     */
    private void injectReasoningOff(ObjectNode json) {
        if (json.has("reasoning") || json.has("include_reasoning") || json.has("reasoning_effort")
                || json.path("provider").path("require_parameters").asBoolean(false)) {
            return;
        }
        json.putObject("reasoning").put("enabled", false);
    }

    /**
     * Pede à OpenRouter tokens/cached/custo reais (sondado 2026-10-10: 200 com tools + require_parameters,
     * stream e não-stream). Nunca sobrescreve o que o chamador mandou.
     */
    private void injectUsageAccounting(ObjectNode json) {
        if (!json.has("usage")) {
            json.putObject("usage").put("include", true);
        }
        if (json.path("stream").asBoolean(false) && !json.has("stream_options")) {
            json.putObject("stream_options").put("include_usage", true);
        }
    }

    /**
     * Prompt caching da Anthropic via OpenRouter (D11): breakpoint no {@code system} e na última
     * mensagem {@code user}, então tools + system + histórico + pergunta viram prefixo reaproveitado
     * nas iterações seguintes do loop de tools. Nunca em {@code tool}/{@code assistant}; não mexe se o
     * chamador já trouxe algum {@code cache_control}.
     */
    private void injectPromptCache(ObjectNode json) {
        if (!json.path("model").asText("").startsWith("anthropic/")
                || !(json.get("messages") instanceof ArrayNode messages) || hasCacheControl(messages)) {
            return;
        }
        ObjectNode system = null;
        ObjectNode lastUser = null;
        for (JsonNode m : messages) {
            if (!(m instanceof ObjectNode msg)) {
                continue;
            }
            String role = msg.path("role").asText("");
            if ("system".equals(role) && system == null) {
                system = msg;
            } else if ("user".equals(role)) {
                lastUser = msg;
            }
        }
        markCacheBreakpoint(system);
        markCacheBreakpoint(lastUser);
    }

    private static boolean hasCacheControl(ArrayNode messages) {
        for (JsonNode m : messages) {
            if (m.has("cache_control")) {
                return true;
            }
            JsonNode content = m.get("content");
            if (content != null && content.isArray()) {
                for (JsonNode part : content) {
                    if (part.has("cache_control")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void markCacheBreakpoint(ObjectNode message) {
        if (message == null) {
            return;
        }
        JsonNode content = message.get("content");
        if (content != null && content.isTextual() && !content.asText().isBlank()) {
            ArrayNode parts = mapper.createArrayNode();
            ObjectNode part = parts.addObject();
            part.put("type", "text");
            part.put("text", content.asText());
            part.putObject("cache_control").put("type", "ephemeral");
            message.set("content", parts);
        } else if (content != null && content.isArray() && !content.isEmpty()
                && content.get(content.size() - 1) instanceof ObjectNode last) {
            last.putObject("cache_control").put("type", "ephemeral");
        }
    }
}
