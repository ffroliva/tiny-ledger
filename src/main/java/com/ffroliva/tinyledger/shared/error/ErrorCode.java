package com.ffroliva.tinyledger.shared.error;

/**
 * Spec §6.5: the error catalogue, as one enum. Framework-free by design — this lives in the open
 * kernel that {@code domain} compiles against, so a Spring type here would put spring-web on the
 * domain's transitive compile path. The HTTP status is an {@code int}, not an {@code HttpStatus}.
 *
 * <p>The message key is resolved against a {@code MessageSource} at the boundary, so the human-readable
 * half can be localised later without touching the machine-readable {@code type}, which is the part
 * clients match on (RFC 7807 says {@code title} should not vary between occurrences).
 */
public enum ErrorCode {
    INVALID_AMOUNT(400, "/errors/invalid-amount", "Invalid amount"),
    UNAUTHENTICATED(401, "/errors/unauthenticated", "Unauthenticated"),
    FORBIDDEN(403, "/errors/forbidden", "Forbidden"),
    ACCOUNT_NOT_FOUND(404, "/errors/account-not-found", "Account not found"),
    IDEMPOTENCY_CONFLICT(409, "/errors/idempotency-conflict", "Idempotency conflict"),
    VERSION_CONFLICT(409, "/errors/version-conflict", "Version conflict"),
    ACCOUNT_LIMIT_REACHED(409, "/errors/account-limit-reached", "Account limit reached"),
    CURRENCY_MISMATCH(422, "/errors/currency-mismatch", "Currency mismatch"),
    INSUFFICIENT_FUNDS(422, "/errors/insufficient-funds", "Insufficient funds"),
    ASSET_MISMATCH(422, "/errors/asset-mismatch", "Asset mismatch"),
    INSUFFICIENT_HOLDING(422, "/errors/insufficient-holding", "Insufficient holding"),
    RATE_LIMIT_EXCEEDED(429, "/errors/rate-limit-exceeded", "Rate limit exceeded"),
    NOT_AVAILABLE_IN_STANDALONE(501, "/errors/not-available-in-standalone", "Not available in standalone"),
    EVENT_STORE_UNAVAILABLE(503, "/errors/event-store-unavailable", "Event store unavailable");

    private final int status;
    private final String type;
    private final String title;

    ErrorCode(int status, String type, String title) {
        this.status = status;
        this.type = type;
        this.title = title;
    }

    public int status() {
        return status;
    }

    public String type() {
        return type;
    }

    /** The stable, developer-facing title. Not localised — see the class javadoc. */
    public String title() {
        return title;
    }

    /** Derived, not declared, so there is no second thing to keep in sync. */
    public String messageKey() {
        return "problem." + type.substring("/errors/".length());
    }
}
