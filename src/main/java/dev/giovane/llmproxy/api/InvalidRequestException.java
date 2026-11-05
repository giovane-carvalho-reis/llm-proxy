package dev.giovane.llmproxy.api;

/** Client sent a body the proxy cannot route (not a JSON object); mapped to 400 by {@code ApiExceptionHandler}. */
public class InvalidRequestException extends RuntimeException {

    private final String code;

    public InvalidRequestException(String message) {
        this(message, null);
    }

    /** {@code code} (opcional) vai no campo {@code error.code} da resposta 400. */
    public InvalidRequestException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
