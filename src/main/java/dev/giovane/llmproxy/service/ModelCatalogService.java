package dev.giovane.llmproxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.giovane.llmproxy.config.ProxyProperties;
import dev.giovane.llmproxy.upstream.UpstreamFailure;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

/** Health and model catalogs (local llama.cpp, filtered local chat models, cached OpenRouter structured-output models). */
@Service
public class ModelCatalogService {

    // Cache manual (sem lib nova) do catálogo filtrado da OpenRouter — a chamada em openrouterModels()
    // é feita a cada abertura da tela de reparo de dividendos; sem cache isso vira uma requisição à
    // OpenRouter por visita. TTL de 10min: catálogo de modelos muda em dias/semanas, não em minutos.
    // ponytail: TTL fixo, sem invalidação manual — se um modelo sumir/aparecer no meio da janela, o
    // operador só vê depois de 10min; ok pra esse caso de uso.
    private static final Duration OPENROUTER_MODELS_TTL = Duration.ofMinutes(10);

    private record CachedCatalog(String body, Instant at) { }

    private final AtomicReference<CachedCatalog> openrouterModelsCache = new AtomicReference<>();
    // Single-flight: só uma thread renova; as demais devolvem o catálogo vencido se houver, em vez
    // de esperar a OpenRouter; com cache frio elas esperam a renovação.
    // Erro do upstream não é cacheado; havendo catálogo vencido, ele é servido no lugar do erro (ADR-015).
    private final ReentrantLock openrouterModelsRefresh = new ReentrantLock();

    private final ProxyProperties props;
    private final ObjectMapper mapper;
    private final UpstreamHttp http;
    private final RequestLogger requestLog;

    ModelCatalogService(ProxyProperties props, ObjectMapper mapper, UpstreamHttp http, RequestLogger requestLog) {
        this.props = props;
        this.mapper = mapper;
        this.http = http;
        this.requestLog = requestLog;
    }

    /** EmbeddingClient.is_available() — relayed to llama-swap's /health. */
    public ResponseEntity<String> health() {
        return http.get(props.embeddings().baseUrl() + "/health");
    }

    /** is_available() health check — proxied to llama.cpp (the default/local backend). */
    public ResponseEntity<String> models() {
        return http.get(props.llamaCpp().baseUrl() + "/v1/models");
    }

    /**
     * Same upstream as {@link #models()}, filtered to {@code llm-proxy.local-chat-models} —
     * llama-swap's /v1/models lists embeddings/rerank models too and carries no group field
     * to tell them apart, so the chat/embeddings split can only come from our own config.
     */
    public ResponseEntity<String> chatModels() {
        Set<String> allowed = Set.copyOf(props.localChatModels());
        return filterCatalog(models(), false, model -> allowed.contains(model.path("id").asText()));
    }

    /**
     * Modelos da OpenRouter elegíveis pro pipeline de extração de dividendos: precisam suportar
     * {@code structured_outputs} (JSON Schema estrito) — é o que {@code _openai_strict_schema}
     * exige no cvm-pdf-processor (ver ADR-035). O catálogo completo da OpenRouter tem ~425
     * modelos, a maioria irrelevante pra esse uso; filtra pra só os utilizáveis aqui.
     */
    public ResponseEntity<String> openrouterModels() {
        CachedCatalog cached = openrouterModelsCache.get();
        if (isFresh(cached)) {
            return catalogResponse(cached.body());
        }
        if (!openrouterModelsRefresh.tryLock()) {
            if (cached != null) {
                return catalogResponse(cached.body());
            }
            openrouterModelsRefresh.lock();
        }
        try {
            return refreshUnderLock();
        } finally {
            openrouterModelsRefresh.unlock();
        }
    }

    private ResponseEntity<String> refreshUnderLock() {
        CachedCatalog cached = openrouterModelsCache.get();
        if (isFresh(cached)) {
            return catalogResponse(cached.body());
        }
        ResponseEntity<String> fetched;
        try {
            fetched = fetchOpenrouterModels();
        } catch (RestClientException e) {
            boolean upstreamDown = UpstreamFailure.classify(e) != null;
            if (upstreamDown) {
                requestLog.backendDownThrottled(props.openrouter().baseUrl(), "/v1/models", e);
            }
            if (upstreamDown && cached != null) {
                return catalogResponse(cached.body());
            }
            throw e;  // ApiExceptionHandler: 502/504 upstream_unavailable (ou 500 api_error)
        }
        if (!fetched.getStatusCode().is2xxSuccessful() && cached != null) {
            return catalogResponse(cached.body());
        }
        return fetched;
    }

    private static boolean isFresh(CachedCatalog cached) {
        return cached != null && Instant.now().isBefore(cached.at().plus(OPENROUTER_MODELS_TTL));
    }

    private static ResponseEntity<String> catalogResponse(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private ResponseEntity<String> fetchOpenrouterModels() {
        Instant now = Instant.now();
        ResponseEntity<String> upstream = http.getCatalog(props.openrouter().baseUrl() + "/v1/models");
        ResponseEntity<String> filtered = filterCatalog(upstream, true, ModelCatalogService::supportsStructuredOutputs);
        if (filtered != upstream && filtered.getBody() != null) {
            openrouterModelsCache.set(new CachedCatalog(filtered.getBody(), now));
        }
        return filtered;
    }

    private static boolean supportsStructuredOutputs(JsonNode model) {
        JsonNode supported = model.path("supported_parameters");
        return supported.isArray() && StreamSupport.stream(supported.spliterator(), false)
                .anyMatch(p -> "structured_outputs".equals(p.asText()));
    }

    /**
     * Filters {@code data[]} of a 2xx JSON-object catalog; anything else (non-2xx, no body, not an object,
     * unparseable) is returned untouched (N5). {@code asCatalogResponse} replaces the upstream headers by a
     * plain JSON content type (the cached OpenRouter shape) instead of keeping only the upstream Content-Type.
     */
    private ResponseEntity<String> filterCatalog(ResponseEntity<String> upstream, boolean asCatalogResponse,
            Predicate<JsonNode> keep) {
        if (!upstream.getStatusCode().is2xxSuccessful() || upstream.getBody() == null) {
            return upstream;
        }
        try {
            if (!(mapper.readTree(upstream.getBody()) instanceof ObjectNode json)) {
                return upstream;
            }
            filterData(json, keep);
            String body = mapper.writeValueAsString(json);
            if (asCatalogResponse) {
                return catalogResponse(body);
            }
            // Content-Length in upstream.getHeaders() was computed for the unfiltered body —
            // reusing it here (now shorter) would make the client wait for bytes that never
            // arrive, hanging until its read timeout. Only Content-Type carries over; Spring
            // MVC computes the correct Content-Length itself from the actual response body.
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(upstream.getHeaders().getContentType());
            return ResponseEntity.status(upstream.getStatusCode()).headers(headers).body(body);
        } catch (IOException e) {
            return upstream;
        }
    }

    private void filterData(ObjectNode json, Predicate<JsonNode> keep) {
        if (json.get("data") instanceof ArrayNode array) {
            ArrayNode filtered = mapper.createArrayNode();
            array.forEach(model -> {
                if (keep.test(model)) {
                    filtered.add(model);
                }
            });
            json.set("data", filtered);
        }
    }
}
