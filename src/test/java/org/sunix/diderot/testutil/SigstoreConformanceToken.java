package org.sunix.diderot.testutil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import dev.sigstore.oidc.client.TokenStringOidcClient;

/**
 * Fetches the "untrusted testing token" the sigstore project publishes for exactly this purpose:
 * exercising real keyless signing against the sigstore STAGING instance without an interactive
 * browser OIDC flow. sigstore-java's own test suite uses the same mechanism (see
 * {@code dev.sigstore.testkit.oidc.ConformanceTestingToken} in the sigstore-java source tree —
 * that module is Gradle-internal and not published to Maven Central, so this hits the same public
 * URL directly instead of depending on it). Never use this token for real signing.
 */
public final class SigstoreConformanceToken {

    private static final String TOKEN_URL =
            "https://storage.googleapis.com/sigstore-conformance-testing-token/untrusted-testing-token.txt";

    private SigstoreConformanceToken() {
    }

    public static String fetch() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch the sigstore conformance-testing token: HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** Wraps {@link #fetch()} as a {@link TokenStringOidcClient.TokenStringProvider}. */
    public static TokenStringOidcClient.TokenStringProvider provider() {
        return new TokenStringOidcClient.TokenStringProvider() {
            @Override
            public String getTokenString(Map<String, String> env) throws Exception {
                return fetch();
            }

            @Override
            public boolean isEnabled(Map<String, String> env) {
                return true;
            }
        };
    }
}
