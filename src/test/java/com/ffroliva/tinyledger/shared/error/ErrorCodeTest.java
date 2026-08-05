package com.ffroliva.tinyledger.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {

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
                        "/errors/version-conflict",
                        "/errors/idempotency-conflict",
                        "/errors/rate-limit-exceeded",
                        "/errors/unauthenticated",
                        "/errors/forbidden",
                        "/errors/account-not-found",
                        "/errors/event-store-unavailable",
                        "/errors/not-available-in-standalone");
    }
}
