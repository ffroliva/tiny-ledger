package com.ffroliva.tinyledger.testsupport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Mints real tokens from the container via Keycloak's password grant, so the suite proves the production
 * {@code issuer-uri} decoder validates an actual Keycloak-issued token rather than merely still working.
 */
public final class KeycloakTokens {

    /** Pinned in the realm file so `sub` is deterministic. §6.4's ownership term is the subject. */
    public static final Map<String, String> SUBJECTS = Map.of(
            "alice", "00000000-0000-4000-8000-000000000001",
            "bob", "00000000-0000-4000-8000-000000000002",
            "carol", "00000000-0000-4000-8000-000000000003",
            "dave", "00000000-0000-4000-8000-000000000004",
            "mallory", "00000000-0000-4000-8000-000000000005",
            "nobody", "00000000-0000-4000-8000-000000000006",
            "trent", "00000000-0000-4000-8000-000000000007");

    private KeycloakTokens() {}

    public static String accessToken(String baseUrl, String username) {
        return accessToken(baseUrl, username, "ledger-test");
    }

    /**
     * Task 3: {@code ledger-other} is a second fixture client with no audience mapper of its own, so a
     * token minted here never carries {@code tiny-ledger-api} — the negative half of the audience proof.
     */
    public static String accessToken(String baseUrl, String username, String clientId) {
        String form = "grant_type=password&client_id=" + clientId + "&username=" + username + "&password=dev-only";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "token request for " + username + " failed: " + response.statusCode() + " " + response.body());
            }
            return new ObjectMapper()
                    .readTree(response.body())
                    .get("access_token")
                    .asText();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("token request for " + username + " failed", e);
        }
    }
}
