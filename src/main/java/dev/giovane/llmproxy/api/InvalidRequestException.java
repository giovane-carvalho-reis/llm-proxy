package dev.giovane.llmproxy.api;

/** Client sent a body the proxy cannot route (not a JSON object); mapped to 400 by {@code ApiExceptionHandler}. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
