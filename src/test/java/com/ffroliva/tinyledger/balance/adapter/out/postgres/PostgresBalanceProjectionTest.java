package com.ffroliva.tinyledger.balance.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ffroliva.tinyledger.ledger.domain.AssetClass;
import com.ffroliva.tinyledger.ledger.domain.AssetTransferred;
import com.ffroliva.tinyledger.ledger.domain.Quantity;
import com.ffroliva.tinyledger.ledger.domain.TaxLot;
import com.ffroliva.tinyledger.ledger.domain.TaxLotSelector;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class PostgresBalanceProjectionTest {

    private JdbcTemplate jdbcTemplate;
    private PostgresBalanceProjection projection;
    private static final Currency GBP = Currency.getInstance("GBP");
    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        projection = new PostgresBalanceProjection(jdbcTemplate);
    }

    @Test
    void appliesAssetTransferredInbound() {
        AccountId accountId = AccountId.random();
        given(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(accountId.value())))
                .willReturn(List.of(1L));

        AssetTransferred event = new AssetTransferred(
                accountId,
                2,
                NOW,
                UUID.randomUUID(),
                Quantity.of("VOO", AssetClass.EQUITY_ETF, "10.000000"),
                new Money(GBP, 4000_00),
                List.of(new TaxLot(
                        "lot-1", Quantity.of("VOO", AssetClass.EQUITY_ETF, "10.000000"), new Money(GBP, 4000_00), NOW)),
                TaxLotSelector.FIFO,
                "buy VOO",
                new Money(GBP, 5000_00),
                "alice");

        projection.apply(event);

        verify(jdbcTemplate)
                .update(
                        eq(
                                "UPDATE balance_projections SET balance_minor_units = ?, stream_version = ?, as_of = ?, currency = ? WHERE account_id = ?"),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(accountId.value()));
    }

    @Test
    void appliesAssetTransferredOutbound() {
        AccountId accountId = AccountId.random();
        given(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(accountId.value())))
                .willReturn(List.of(2L));

        AssetTransferred event = new AssetTransferred(
                accountId,
                3,
                NOW,
                UUID.randomUUID(),
                Quantity.of("VOO", AssetClass.EQUITY_ETF, "-5.000000"),
                new Money(GBP, 2000_00),
                List.of(new TaxLot(
                        "lot-1", Quantity.of("VOO", AssetClass.EQUITY_ETF, "5.000000"), new Money(GBP, 2000_00), NOW)),
                TaxLotSelector.HIFO,
                "sell VOO",
                new Money(GBP, 7000_00),
                "alice");

        projection.apply(event);

        verify(jdbcTemplate)
                .update(
                        eq(
                                "UPDATE balance_projections SET balance_minor_units = ?, stream_version = ?, as_of = ?, currency = ? WHERE account_id = ?"),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(accountId.value()));
    }

    @Test
    void skipsAlreadyAppliedAssetTransferred() {
        AccountId accountId = AccountId.random();
        given(jdbcTemplate.queryForList(anyString(), eq(Long.class), eq(accountId.value())))
                .willReturn(List.of(5L));

        AssetTransferred event = new AssetTransferred(
                accountId,
                3,
                NOW,
                UUID.randomUUID(),
                Quantity.of("VOO", AssetClass.EQUITY_ETF, "5.000000"),
                new Money(GBP, 2000_00),
                List.of(),
                null,
                null,
                new Money(GBP, 7000_00),
                "alice");

        projection.apply(event);

        assertThat(event.version()).isEqualTo(3);
    }
}
