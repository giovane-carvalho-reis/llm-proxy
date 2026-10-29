package dev.giovane.llmproxy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.giovane.llmproxy.config.LlmConfigState;
import dev.giovane.llmproxy.config.ProxyProperties;
import dev.giovane.llmproxy.service.TestServices;

import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** I1: malformed or non-object bodies reach the caller as 400 in the OpenAI error shape, not 500. */
class ApiExceptionHandlerTest {

    private final MockMvc mvc;

    ApiExceptionHandlerTest() {
        // Upstream never reached: parsing fails before any HTTP call.
        ProxyProperties.Upstream up = new ProxyProperties.Upstream("http://localhost:1", null, "m", null);
        ProxyProperties props = new ProxyProperties(up, up, up, up,
                new ProxyProperties.Routing(8000, Duration.ofMinutes(1)), "", List.of(), List.of());
        ObjectMapper mapper = new ObjectMapper();
        mvc = MockMvcBuilders.standaloneSetup(new ChatController(
                        TestServices.proxy(props, new LlmConfigState(), mapper), TestServices.catalog(props, mapper)))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void invalidBodiesReturn400InOpenAiShape() throws Exception {
        for (String path : List.of("/v1/chat/completions", "/v1/embeddings", "/v1/rerank")) {
            for (String body : List.of("[]", "\"x\"", "{")) {
                mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                        .andExpect(jsonPath("$.error.message").isNotEmpty());
            }
        }
    }

    private static MockMvc mvcFor(String baseUrl, Duration read, Duration catalogRead) {
        ProxyProperties.Upstream up = new ProxyProperties.Upstream(baseUrl, null, "m", null);
        ProxyProperties props = new ProxyProperties(up, up, up, up,
                new ProxyProperties.Routing(8000, read, catalogRead), "", List.of(), List.of());
        return MockMvcBuilders.standaloneSetup(new ChatController(
                        TestServices.proxy(props, new LlmConfigState(), new ObjectMapper()),
                        TestServices.catalog(props, new ObjectMapper())))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static HttpServer hangingServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void connectionRefusedReturns502UpstreamUnavailable() throws Exception {
        mvcFor("http://localhost:1", Duration.ofMinutes(1), null)
                .perform(post("/v1/embeddings").contentType(MediaType.APPLICATION_JSON).content("{\"input\":\"x\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.type").value("upstream_unavailable"))
                .andExpect(jsonPath("$.error.message").value(Matchers.startsWith("backend indisponível: ")));
    }

    @Test
    void upstreamThatDoesNotAnswerWithinReadTimeoutReturns504() throws Exception {
        HttpServer server = hangingServer();
        try {
            mvcFor("http://localhost:" + server.getAddress().getPort(), Duration.ofMillis(300), null)
                    .perform(post("/v1/embeddings").contentType(MediaType.APPLICATION_JSON).content("{\"input\":\"x\"}"))
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(jsonPath("$.error.type").value("upstream_unavailable"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void hangingOpenrouterCatalogReturns504WithinCatalogTimeout() throws Exception {
        HttpServer server = hangingServer();
        try {
            long start = System.currentTimeMillis();
            mvcFor("http://localhost:" + server.getAddress().getPort(), Duration.ofMinutes(1), Duration.ofSeconds(1))
                    .perform(get("/v1/models/openrouter"))
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(jsonPath("$.error.type").value("upstream_unavailable"));
            Assertions.assertThat(System.currentTimeMillis() - start).isLessThan(4000);
        } finally {
            server.stop(0);
        }
    }

    /** Headers com Content-Length maior que o corpo e conexão pendurada: timeout acontece na leitura do corpo. */
    private static HttpServer truncatedBodyServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 1000);
            exchange.getResponseBody().write("{\"da".getBytes());
            exchange.getResponseBody().flush();
            try {
                new CountDownLatch(1).await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    void timeoutWhileReadingBodyReturns504AndLogsBackendUnavailable() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("dev.giovane.llmproxy.service");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        HttpServer server = truncatedBodyServer();
        try {
            mvcFor("http://localhost:" + server.getAddress().getPort(), Duration.ofMillis(300), null)
                    .perform(post("/v1/embeddings").contentType(MediaType.APPLICATION_JSON).content("{\"input\":\"x\"}"))
                    .andExpect(status().isGatewayTimeout())
                    .andExpect(jsonPath("$.error.type").value("upstream_unavailable"));
        } finally {
            server.stop(0);
            logger.detachAppender(appender);
        }
        Assertions.assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR
                        && e.getFormattedMessage().contains("backend indisponível"));
    }

    @RestController
    static class BoomController {
        @GetMapping("/boom")
        String boom() {
            throw new RestClientException("falha inesperada");
        }
    }

    @Test
    void unclassifiedRestClientExceptionReturns500ApiErrorAndIsLogged() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockMvcBuilders.standaloneSetup(new BoomController()).setControllerAdvice(new ApiExceptionHandler()).build()
                    .perform(get("/boom"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error.type").value("api_error"));
        } finally {
            logger.detachAppender(appender);
        }
        Assertions.assertThat(appender.list)
                .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR
                        && e.getFormattedMessage().contains("restclient inesperado")
                        && e.getThrowableProxy() != null);
    }
}
