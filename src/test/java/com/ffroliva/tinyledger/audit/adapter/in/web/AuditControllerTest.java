package com.ffroliva.tinyledger.audit.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ffroliva.tinyledger.audit.application.port.out.AuditTrailPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Spec §7: the auditor pair, served from the audit module's own store in {@code full} and refused in
 * {@code standalone}. The two run modes are two contexts: this one has no {@link AuditTrailPort} bean —
 * exactly what {@code standalone} composes — and the nested one has the trail wired.
 */
@WebMvcTest(AuditController.class)
class AuditControllerTest {

    private static final UUID ACCOUNT = UUID.fromString("f91e6c0e-1f3d-4b2a-9c77-0b1c2d3e4f50");
    private static final Instant OCCURRED = Instant.parse("2026-08-04T09:15:00Z");
    private static final Instant RECORDED = Instant.parse("2026-08-04T09:15:01Z");
    private static final String CURSOR = "eyJ0IjoxNzU0MzE1MTI5fQ";

    @Autowired
    private MockMvc mvc;

    @Test // §6.5/§7
    void rawEventStreamIsNotAvailableWithoutAnAuditTrail() throws Exception {
        mvc.perform(get("/api/v1/accounts/{a}/events", ACCOUNT))
                .andExpect(status().isNotImplemented())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("/errors/not-available-in-standalone"))
                .andExpect(jsonPath("$.status").value(501));
    }

    @Test // §6.5/§7
    void auditTrailIsNotAvailableWithoutAnAuditTrail() throws Exception {
        mvc.perform(get("/api/v1/audit/entries"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.type").value("/errors/not-available-in-standalone"));
    }

    @Nested // the full profile: FullAdapterConfig contributes the trail
    class WithAnAuditTrailWired {

        @MockitoBean
        private AuditTrailPort trail;

        @Test // §7: the account's own events, in stream order, as the trail holds them
        void eventStreamIsServedFromTheAuditTrail() throws Exception {
            given(trail.eventStream(ACCOUNT, null, 50)).willReturn(page(null));

            mvc.perform(get("/api/v1/accounts/{a}/events", ACCOUNT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.events[0].accountUid").value(ACCOUNT.toString()))
                    .andExpect(jsonPath("$.events[0].version").value(1))
                    .andExpect(jsonPath("$.events[0].type").value("AccountOpened"))
                    .andExpect(jsonPath("$.events[0].occurredAt").value("2026-08-04T09:15:00Z"))
                    .andExpect(jsonPath("$.events[1].type").value("MoneyDeposited"))
                    .andExpect(jsonPath("$.events[1].payload.amount.minorUnits").value(2500))
                    .andExpect(jsonPath("$.links.next").doesNotExist());
        }

        @Test // §7: links.next is a URL — the same path carrying the opaque cursor
        void eventStreamCarriesTheNextPageUrl() throws Exception {
            given(trail.eventStream(ACCOUNT, null, 50)).willReturn(page(CURSOR));

            mvc.perform(get("/api/v1/accounts/{a}/events", ACCOUNT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.links.next")
                            .value("/api/v1/accounts/" + ACCOUNT + "/events?limit=50&cursor=" + CURSOR));
        }

        @Test // a next link that dropped the caller's limit would silently reset it to the default
        void eventStreamNextPageUrlKeepsTheCallersLimit() throws Exception {
            given(trail.eventStream(ACCOUNT, null, 25)).willReturn(page(CURSOR));

            mvc.perform(get("/api/v1/accounts/{a}/events", ACCOUNT).param("limit", "25"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.links.next")
                            .value("/api/v1/accounts/" + ACCOUNT + "/events?limit=25&cursor=" + CURSOR));
        }

        @Test // §7: the trail is filterable by account and time range; recordedAt is the Kafka hop
        void auditTrailIsFilteredByAccountAndTimeRange() throws Exception {
            given(trail.trail(new AuditTrailPort.TrailQuery(
                            ACCOUNT,
                            null,
                            50,
                            Instant.parse("2026-08-04T00:00:00Z"),
                            Instant.parse("2026-08-04T23:59:59Z"))))
                    .willReturn(page(null));

            mvc.perform(get("/api/v1/audit/entries")
                            .param("accountUid", ACCOUNT.toString())
                            .param("minTransactionTimestamp", "2026-08-04T00:00:00Z")
                            .param("maxTransactionTimestamp", "2026-08-04T23:59:59Z"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.auditEntries[0].accountUid").value(ACCOUNT.toString()))
                    .andExpect(jsonPath("$.auditEntries[0].version").value(1))
                    .andExpect(jsonPath("$.auditEntries[0].type").value("AccountOpened"))
                    .andExpect(jsonPath("$.auditEntries[0].occurredAt").value("2026-08-04T09:15:00Z"))
                    .andExpect(jsonPath("$.auditEntries[0].recordedAt").value("2026-08-04T09:15:01Z"))
                    .andExpect(jsonPath("$.auditEntries[1].type").value("MoneyDeposited"));
        }

        @Test // a next link that dropped the filters would page over a different result set
        void auditTrailNextPageUrlKeepsTheFilters() throws Exception {
            given(trail.trail(new AuditTrailPort.TrailQuery(ACCOUNT, null, 50, null, null)))
                    .willReturn(page(CURSOR));

            mvc.perform(get("/api/v1/audit/entries").param("accountUid", ACCOUNT.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.links.next")
                            .value("/api/v1/audit/entries?accountUid=" + ACCOUNT + "&limit=50&cursor=" + CURSOR));
        }

        @Test // a next link that dropped the caller's limit would page over a different result set
        void auditTrailNextPageUrlKeepsTheCallersLimit() throws Exception {
            given(trail.trail(new AuditTrailPort.TrailQuery(ACCOUNT, null, 25, null, null)))
                    .willReturn(page(CURSOR));

            mvc.perform(get("/api/v1/audit/entries")
                            .param("accountUid", ACCOUNT.toString())
                            .param("limit", "25"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.links.next")
                            .value("/api/v1/audit/entries?accountUid=" + ACCOUNT + "&limit=25&cursor=" + CURSOR));
        }

        @Test // a bare '+' in a query value decodes back as a space, so an echoed offset must be escaped
        void auditTrailNextPageUrlPercentEncodesTimestampOffsets() throws Exception {
            given(trail.trail(new AuditTrailPort.TrailQuery(
                            ACCOUNT,
                            null,
                            50,
                            OffsetDateTime.parse("2026-08-04T00:00:00+01:00").toInstant(),
                            null)))
                    .willReturn(page(CURSOR));

            mvc.perform(get("/api/v1/audit/entries")
                            .param("accountUid", ACCOUNT.toString())
                            .param("minTransactionTimestamp", "2026-08-04T00:00:00+01:00"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.links.next")
                            .value("/api/v1/audit/entries?accountUid=" + ACCOUNT
                                    + "&minTransactionTimestamp=2026-08-04T00:00%2B01:00&limit=50&cursor="
                                    + CURSOR))
                    .andExpect(jsonPath("$.links.next").value(not(containsString("+"))));
        }

        @Test // §6.5: the contract's limit bounds are enforced at the edge, before the trail is read
        void limitOutsideTheContractRangeIsBadRequest() throws Exception {
            mvc.perform(get("/api/v1/accounts/{a}/events", ACCOUNT).param("limit", "201"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("/errors/invalid-amount"));

            verifyNoInteractions(trail);
        }

        @Test // §7/D4: the audit entry surfaces the acting principal; absent stays absent on the wire
        void auditTrailSurfacesTheActor() throws Exception {
            given(trail.trail(new AuditTrailPort.TrailQuery(ACCOUNT, null, 50, null, null)))
                    .willReturn(page(null));

            mvc.perform(get("/api/v1/audit/entries").param("accountUid", ACCOUNT.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.auditEntries[0].actor").doesNotExist())
                    .andExpect(jsonPath("$.auditEntries[1].actor").value("alice"));
        }

        private static AuditTrailPort.Page page(String nextCursor) {
            return new AuditTrailPort.Page(
                    List.of(
                            new AuditTrailPort.AuditEntry(ACCOUNT, "AccountOpened", 1, OCCURRED, RECORDED, """
                                    {"name":"ACC-001","owner":"alice"}""", null),
                            new AuditTrailPort.AuditEntry(
                                    ACCOUNT, "MoneyDeposited", 2, OCCURRED, RECORDED, """
                                    {"amount":{"currency":"GBP","minorUnits":2500}}""", "alice")),
                    nextCursor);
        }
    }
}
