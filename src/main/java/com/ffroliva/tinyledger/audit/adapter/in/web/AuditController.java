package com.ffroliva.tinyledger.audit.adapter.in.web;

import com.ffroliva.tinyledger.api.generated.api.AuditApi;
import com.ffroliva.tinyledger.api.generated.model.AuditEntry;
import com.ffroliva.tinyledger.api.generated.model.AuditEntryList;
import com.ffroliva.tinyledger.api.generated.model.Event;
import com.ffroliva.tinyledger.api.generated.model.EventList;
import com.ffroliva.tinyledger.api.generated.model.PageLinks;
import com.ffroliva.tinyledger.audit.application.port.out.AuditTrailPort;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The auditor-facing inbound adapter (spec §7, §6.4).
 *
 * <p>Both operations are answered from the audit module's own trail — the module reads no ledger port and
 * no ledger store, which is what keeps §4.3's Kafka boundary a real one. It implements {@code AuditApi}
 * whole and nothing else: {@code MovementsApi} and {@code AuditApi} each declare a {@code default
 * getRequest()}, and Java refuses to inherit the same default twice.
 *
 * <p>The trail exists only where {@code FullAdapterConfig} composes it, so an absent {@link AuditTrailPort}
 * <em>is</em> {@code standalone} — and §6.5's 501 is the honest answer rather than a profile check smuggled
 * into a controller.
 *
 * <p>§6.4's {@code ledger:auditor} check is the composition root's authorisation decorator, which arrives
 * with Keycloak; until then this controller enforces exactly what the rest of the API does — nothing —
 * rather than inventing a second, parallel mechanism.
 */
@RestController
public class AuditController implements AuditApi {

    private final Optional<AuditTrailPort> trail;
    private final ObjectMapper objectMapper;

    public AuditController(Optional<AuditTrailPort> trail, ObjectMapper objectMapper) {
        this.trail = trail;
        this.objectMapper = objectMapper;
    }

    /**
     * An account with no entries answers an empty page, not a 404: the trail is eventually consistent
     * (§4.3), so "nothing here" cannot be told apart from "not consumed yet" and a 404 would be a guess.
     */
    @Override
    public ResponseEntity<EventList> getEvents(UUID accountUid, String cursor, Integer limit) {
        AuditTrailPort.Page page = available().eventStream(accountUid, cursor, limit);
        EventList body = new EventList(page.entries().stream().map(this::event).toList());
        if (page.nextCursor() != null) {
            // §7: the next page is the same query one cursor further on — dropping the caller's limit
            // here would silently reset it to the default on every subsequent page.
            body.links(new PageLinks()
                    .next(UriComponentsBuilder.fromPath("/api/v1/accounts/" + accountUid + "/events")
                            .queryParamIfPresent("limit", encodedIfPresent(limit))
                            .queryParam("cursor", encoded(page.nextCursor()))
                            .build(true)
                            .toUriString()));
        }
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<AuditEntryList> listAuditEntries(
            UUID accountUid,
            String cursor,
            Integer limit,
            OffsetDateTime minTransactionTimestamp,
            OffsetDateTime maxTransactionTimestamp) {
        AuditTrailPort.Page page = available()
                .trail(new AuditTrailPort.TrailQuery(
                        accountUid, cursor, limit, instant(minTransactionTimestamp), instant(maxTransactionTimestamp)));
        AuditEntryList body = new AuditEntryList(
                page.entries().stream().map(AuditController::auditEntry).toList());
        if (page.nextCursor() != null) {
            // §7: the next page is the same query one cursor further on — dropping the filters or the
            // caller's limit here would silently page over a different result set.
            body.links(new PageLinks()
                    .next(UriComponentsBuilder.fromPath("/api/v1/audit/entries")
                            .queryParamIfPresent("accountUid", encodedIfPresent(accountUid))
                            .queryParamIfPresent("minTransactionTimestamp", encodedIfPresent(minTransactionTimestamp))
                            .queryParamIfPresent("maxTransactionTimestamp", encodedIfPresent(maxTransactionTimestamp))
                            .queryParamIfPresent("limit", encodedIfPresent(limit))
                            .queryParam("cursor", encoded(page.nextCursor()))
                            .build(true)
                            .toUriString()));
        }
        return ResponseEntity.ok(body);
    }

    /**
     * Percent-encodes one query value; the builders above are handed pre-encoded components
     * ({@code build(true)}) so nothing is escaped twice. {@code UriUtils} alone is not enough:
     * RFC 3986 lists {@code '+'} as a sub-delimiter and leaves it alone, but a query string's
     * {@code '+'} decodes back as a space — an echoed {@code +01:00} offset would return as
     * {@code " 01:00"} and 400 the very next page.
     */
    private static String encoded(Object value) {
        return UriUtils.encodeQueryParam(value.toString(), StandardCharsets.UTF_8)
                .replace("+", "%2B");
    }

    /** An absent optional param is one {@code queryParamIfPresent} leaves out of the link entirely. */
    private static Optional<String> encodedIfPresent(Object value) {
        return Optional.ofNullable(value).map(AuditController::encoded);
    }

    private AuditTrailPort available() {
        return trail.orElseThrow(AuditController::notAvailableInStandalone);
    }

    private Event event(AuditTrailPort.AuditEntry entry) {
        return new Event(
                        entry.accountId(),
                        entry.streamVersion(),
                        Event.TypeEnum.fromValue(entry.eventType()),
                        at(entry.occurredAt()))
                // The trail stores the event verbatim; parsing it back to a map is JSON, not knowledge of
                // the write side's shape.
                .payload(objectMapper.readValue(entry.payload(), new TypeReference<Map<String, Object>>() {}));
    }

    private static AuditEntry auditEntry(AuditTrailPort.AuditEntry entry) {
        return new AuditEntry(
                entry.accountId(),
                entry.streamVersion(),
                AuditEntry.TypeEnum.fromValue(entry.eventType()),
                at(entry.occurredAt()),
                at(entry.recordedAt()));
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** §6.5/§7: the auditor pair exists in the {@code full} profile only. */
    private static ErrorResponseException notAvailableInStandalone() {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.NOT_IMPLEMENTED);
        body.setType(URI.create("/errors/not-available-in-standalone"));
        body.setTitle("Not available in standalone");
        return new ErrorResponseException(HttpStatus.NOT_IMPLEMENTED, body, null);
    }
}
