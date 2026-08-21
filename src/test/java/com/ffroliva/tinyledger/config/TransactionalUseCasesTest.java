package com.ffroliva.tinyledger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ffroliva.tinyledger.ledger.application.port.in.AssetTransfer;
import com.ffroliva.tinyledger.ledger.application.port.in.Deposit;
import com.ffroliva.tinyledger.ledger.application.port.in.MovementResult;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenedAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.Outcome;
import com.ffroliva.tinyledger.ledger.application.port.in.Withdraw;
import com.ffroliva.tinyledger.ledger.application.usecase.OpenAccountService;
import com.ffroliva.tinyledger.ledger.application.usecase.RecordMovementService;
import com.ffroliva.tinyledger.ledger.domain.AssetClass;
import com.ffroliva.tinyledger.ledger.domain.MovementType;
import com.ffroliva.tinyledger.ledger.domain.Quantity;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionalUseCasesTest {

    @Test
    void delegatesOpenAccount() {
        OpenAccountService delegate = mock(OpenAccountService.class);
        TransactionalUseCases.Opening opening = new TransactionalUseCases.Opening(delegate);
        OpenAccount cmd = new OpenAccount("alice", "acc", Currency.getInstance("GBP"));
        OpenedAccount expected = new OpenedAccount(AccountId.random(), 1, Instant.now());
        given(delegate.open(cmd)).willReturn(expected);

        OpenedAccount result = opening.open(cmd);
        assertThat(result).isEqualTo(expected);
        verify(delegate).open(cmd);
    }

    @Test
    void delegatesMovements() {
        RecordMovementService delegate = mock(RecordMovementService.class);
        TransactionalUseCases.Movements movements = new TransactionalUseCases.Movements(delegate);
        AccountId accountId = AccountId.random();
        UUID uid = UUID.randomUUID();
        Instant now = Instant.now();

        Deposit deposit =
                new Deposit("alice", false, accountId, uid, new Money(Currency.getInstance("GBP"), 100), "dep");
        MovementResult expectedDeposit = new MovementResult(
                accountId,
                uid,
                MovementType.DEPOSIT,
                2,
                new Money(Currency.getInstance("GBP"), 100),
                new Money(Currency.getInstance("GBP"), 100),
                now,
                Outcome.CREATED,
                null);
        given(delegate.deposit(deposit)).willReturn(expectedDeposit);
        assertThat(movements.deposit(deposit)).isEqualTo(expectedDeposit);

        Withdraw withdraw =
                new Withdraw("alice", false, accountId, uid, new Money(Currency.getInstance("GBP"), 50), "wit");
        MovementResult expectedWithdraw = new MovementResult(
                accountId,
                uid,
                MovementType.WITHDRAWAL,
                3,
                new Money(Currency.getInstance("GBP"), 50),
                new Money(Currency.getInstance("GBP"), 50),
                now,
                Outcome.CREATED,
                null);
        given(delegate.withdraw(withdraw)).willReturn(expectedWithdraw);
        assertThat(movements.withdraw(withdraw)).isEqualTo(expectedWithdraw);

        Quantity qty = Quantity.of("VOO", AssetClass.EQUITY_ETF, "10.000000");
        Money basis = new Money(Currency.getInstance("GBP"), 4000_00);
        AssetTransfer transfer =
                new AssetTransfer("alice", false, accountId, uid, "IN", qty, basis, "lot-1", null, "trans");
        MovementResult expectedTransfer = new MovementResult(
                accountId,
                uid,
                MovementType.ASSET_TRANSFER,
                4,
                basis,
                new Money(Currency.getInstance("GBP"), 50),
                now,
                Outcome.CREATED,
                null,
                qty,
                List.of());
        given(delegate.transferAsset(transfer)).willReturn(expectedTransfer);
        assertThat(movements.transferAsset(transfer)).isEqualTo(expectedTransfer);
    }
}
