package com.ffroliva.tinyledger.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    /**
     * §6.5: {@code messageKey()} existed with no bundle behind it and no caller, so every problem
     * detail went out without a {@code detail}. This pins the bundle to the catalogue: a new code
     * with no message, or a message whose key nothing produces, is red here rather than a field
     * quietly missing from a response.
     */
    @Test
    void everyCodeResolvesToAMessageAndTheBundleHasNoOrphans() throws IOException {
        Properties bundle = new Properties();
        try (InputStream in = ErrorCode.class.getResourceAsStream("/messages.properties")) {
            assertThat(in).as("messages.properties on the classpath").isNotNull();
            bundle.load(in);
        }

        for (ErrorCode code : ErrorCode.values()) {
            assertThat(bundle.getProperty(code.messageKey()))
                    .as("message for %s (%s)", code, code.messageKey())
                    .isNotNull()
                    .isNotBlank();
        }

        assertThat(bundle.stringPropertyNames())
                .as("keys with no ErrorCode behind them")
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(ErrorCode.values())
                        .map(ErrorCode::messageKey)
                        .toList());
    }

    @Test // §6.5: one catalogue, so every code must carry a complete answer
    void everyCodeHasAStatusATypeAndAMessageKey() {
        assertThat(ErrorCode.values()).isNotEmpty();
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.status()).as("status of %s", code).isBetween(400, 599);
            assertThat(code.type()).as("type of %s", code).startsWith("/errors/");
            assertThat(code.messageKey()).as("messageKey of %s", code).startsWith("problem.");
        }
    }

    @Test // a duplicated type URI would make the catalogue ambiguous at the wire
    void typesAreUnique() {
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::type)).doesNotHaveDuplicates();
    }

    @Test // §6.5 is the authority; this pins the catalogue AGAINST it, so a dropped or invented row is red
    void theCatalogueIsExactlySpecSection6_5() {
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::type))
                .containsExactlyInAnyOrder(
                        "/errors/insufficient-funds",
                        "/errors/invalid-amount",
                        "/errors/currency-mismatch",
                        "/errors/asset-mismatch",
                        "/errors/insufficient-holding",
                        "/errors/version-conflict",
                        "/errors/idempotency-conflict",
                        "/errors/account-limit-reached",
                        "/errors/rate-limit-exceeded",
                        "/errors/unauthenticated",
                        "/errors/forbidden",
                        "/errors/account-not-found",
                        "/errors/event-store-unavailable",
                        "/errors/not-available-in-standalone");
    }
}
