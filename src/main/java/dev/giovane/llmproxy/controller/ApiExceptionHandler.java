package dev.giovane.llmproxy.controller;

import dev.giovane.llmproxy.service.InvalidRequestException;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Maps caller errors to 400 in the OpenAI error shape, so clients' SDKs parse them like upstream errors. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Map<String, Object>> invalidRequest(InvalidRequestException e) {
        return ResponseEntity.badRequest().body(Map.of("error", Map.of(
                "message", e.getMessage(),
                "type", "invalid_request_error")));
    }
}
