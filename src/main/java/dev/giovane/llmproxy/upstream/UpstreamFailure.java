package dev.giovane.llmproxy.upstream;

import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

/** Classifica falha de chamada ao upstream: usada pelo log de backend-down e pelo mapeamento 502/504. */
public enum UpstreamFailure {
    /** Conexão recusada/resetada/sem rota → 502. */
    CONNECT,
    /** Timeout (conexão ou leitura, inclusive no corpo) na cadeia de causas → 504. */
    TIMEOUT;

    /** @return a classe da falha, ou null se não for falha de alcançar o upstream. */
    public static UpstreamFailure classify(RestClientException e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return TIMEOUT;
            }
        }
        return e instanceof ResourceAccessException ? CONNECT : null;
    }
}
