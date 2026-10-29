package dev.giovane.llmproxy.controller;

import dev.giovane.llmproxy.api.InvalidRequestException;
import dev.giovane.llmproxy.upstream.UpstreamFailure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Maps caller errors to 400 in the OpenAI error shape, so clients' SDKs parse them like upstream errors. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Map<String, Object>> invalidRequest(InvalidRequestException e) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of(
                "message", e.getMessage(),
                "type", "invalid_request_error")));
    }

    private static final Pattern URI_IN_MESSAGE = Pattern.compile("for \"([^\"]+)\"");

    /** D7: backend inalcançável → 502; timeout (na cadeia de causas) → 504; demais erros de cliente HTTP → 500 api_error. */
    @ExceptionHandler(RestClientException.class)
    ResponseEntity<Map<String, Object>> restClientFailure(RestClientException e) {
        UpstreamFailure failure = UpstreamFailure.classify(e);
        if (failure == null) {
            log.error("restclient inesperado", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", Map.of(
                    "message", "erro interno ao chamar o backend",
                    "type", "api_error")));
        }
        Matcher uri = URI_IN_MESSAGE.matcher(String.valueOf(e.getMessage()));
        return ResponseEntity.status(failure == UpstreamFailure.TIMEOUT ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", Map.of(
                        "message", "backend indisponível: " + (uri.find() ? uri.group(1) : "upstream"),
                        "type", "upstream_unavailable")));
    }
}
