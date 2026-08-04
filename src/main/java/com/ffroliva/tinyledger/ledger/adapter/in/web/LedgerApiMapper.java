package com.ffroliva.tinyledger.ledger.adapter.in.web;

import com.ffroliva.tinyledger.api.generated.model.Account;
import com.ffroliva.tinyledger.api.generated.model.Balance;
import com.ffroliva.tinyledger.api.generated.model.Money;
import com.ffroliva.tinyledger.api.generated.model.MovementAmount;
import com.ffroliva.tinyledger.api.generated.model.MovementRequest;
import com.ffroliva.tinyledger.api.generated.model.OpenAccountRequest;
import com.ffroliva.tinyledger.api.generated.model.Transaction;
import com.ffroliva.tinyledger.ledger.application.port.in.MovementResult;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenedAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.StrongBalance;
import com.ffroliva.tinyledger.ledger.domain.MovementType;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Spec §4.6: the inbound web adapter owns wire DTO ↔ command and result ↔ response DTO, as hand-written
 * static functions. The generated {@code model.Money} and the domain {@code shared.Money} are different
 * shapes with the same fields; this is the only place that knows both.
 */
final class LedgerApiMapper {

    private LedgerApiMapper() {}

    static OpenAccount toCommand(OpenAccountRequest request, String caller) {
        return new OpenAccount(caller, request.getName(), Currency.getInstance(request.getCurrency()));
    }

    static Account toAccount(OpenedAccount opened, OpenAccountRequest request, String caller) {
        return new Account(
                opened.accountId().value(), request.getName(), request.getCurrency(), at(opened.createdAt()), caller);
    }

    static com.ffroliva.tinyledger.shared.Money toMoney(MovementAmount amount) {
        return com.ffroliva.tinyledger.shared.Money.of(amount.getCurrency(), amount.getMinorUnits());
    }

    static Money toMoney(com.ffroliva.tinyledger.shared.Money money) {
        return new Money(money.currency().getCurrencyCode(), money.minorUnits());
    }

    static Balance toBalance(StrongBalance balance) {
        return new Balance(
                balance.accountId().value(), toMoney(balance.amount()), at(balance.asOf()), balance.streamVersion());
    }

    static Transaction toTransaction(MovementResult result, MovementRequest request) {
        return new Transaction(
                        result.movementUid(),
                        result.accountId().value(),
                        Transaction.TypeEnum.fromValue(result.type().name()),
                        direction(result.type()),
                        toMoney(result.amount()),
                        toMoney(result.balanceAfter()),
                        Transaction.StatusEnum.SETTLED,
                        at(result.occurredAt()),
                        at(result.occurredAt()))
                .reference(request.getReference());
    }

    /**
     * Spec §6.3/§6.5: a refusal is a 422 whose {@code type} is the rejection reason — and it replays as the
     * same 422, which is why {@code REJECTED_REPLAYED} lands here too.
     */
    static ErrorResponseException rejection(MovementResult result) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "/errors/" + result.rejectionReason(),
                switch (result.rejectionReason()) {
                    case "insufficient-funds" -> "Insufficient funds";
                    case "currency-mismatch" -> "Currency mismatch";
                    default -> "Movement rejected";
                });
    }

    static ErrorResponseException problem(HttpStatus status, String type, String title) {
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setType(URI.create(type));
        body.setTitle(title);
        return new ErrorResponseException(status, body, null);
    }

    private static Transaction.DirectionEnum direction(MovementType type) {
        return type == MovementType.DEPOSIT ? Transaction.DirectionEnum.IN : Transaction.DirectionEnum.OUT;
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
