package com.ffroliva.tinyledger.ledger.adapter.in.web;

import com.ffroliva.tinyledger.api.generated.model.Account;
import com.ffroliva.tinyledger.api.generated.model.AssetTransaction;
import com.ffroliva.tinyledger.api.generated.model.AssetTransferRequest;
import com.ffroliva.tinyledger.api.generated.model.Balance;
import com.ffroliva.tinyledger.api.generated.model.Money;
import com.ffroliva.tinyledger.api.generated.model.MovementAmount;
import com.ffroliva.tinyledger.api.generated.model.MovementRequest;
import com.ffroliva.tinyledger.api.generated.model.OpenAccountRequest;
import com.ffroliva.tinyledger.api.generated.model.TaxLotDto;
import com.ffroliva.tinyledger.api.generated.model.Transaction;
import com.ffroliva.tinyledger.ledger.application.port.in.AssetTransfer;
import com.ffroliva.tinyledger.ledger.application.port.in.MovementResult;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.OpenedAccount;
import com.ffroliva.tinyledger.ledger.application.port.in.StrongBalance;
import com.ffroliva.tinyledger.ledger.domain.AssetClass;
import com.ffroliva.tinyledger.ledger.domain.MovementType;
import com.ffroliva.tinyledger.ledger.domain.Quantity;
import com.ffroliva.tinyledger.ledger.domain.TaxLotSelector;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.error.InvalidAmountException;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
        return new OpenAccount(
                caller, request.getName(), com.ffroliva.tinyledger.shared.Money.currencyOf(request.getCurrency()));
    }

    static Account toAccount(OpenedAccount opened, OpenAccountRequest request, String caller) {
        return new Account(
                opened.accountId().value(), request.getName(), request.getCurrency(), at(opened.createdAt()), caller);
    }

    static com.ffroliva.tinyledger.shared.Money toMoney(MovementAmount amount) {
        return com.ffroliva.tinyledger.shared.Money.of(amount.getCurrency(), amount.getMinorUnits());
    }

    static com.ffroliva.tinyledger.shared.Money toMoney(Money money) {
        return money == null
                ? null
                : com.ffroliva.tinyledger.shared.Money.of(money.getCurrency(), money.getMinorUnits());
    }

    static Money toMoney(com.ffroliva.tinyledger.shared.Money money) {
        return money == null ? null : new Money(money.currency().getCurrencyCode(), money.minorUnits());
    }

    static Balance toBalance(StrongBalance balance) {
        return new Balance(
                balance.accountId().value(), toMoney(balance.amount()), at(balance.asOf()), balance.streamVersion());
    }

    static AssetTransfer toCommand(
            UUID accountUid, UUID transferUid, AssetTransferRequest request, String caller, boolean callerIsAdmin) {
        AssetClass assetClass;
        try {
            assetClass = AssetClass.valueOf(request.getAssetClass().name());
        } catch (RuntimeException e) {
            throw new InvalidAmountException("invalid assetClass: " + request.getAssetClass());
        }

        Quantity qty;
        try {
            qty = Quantity.of(request.getAssetSymbol(), assetClass, request.getQuantity());
        } catch (IllegalArgumentException e) {
            throw new InvalidAmountException("invalid quantity: " + request.getQuantity());
        }

        TaxLotSelector selector = null;
        if (request.getSelector() != null) {
            try {
                selector = TaxLotSelector.valueOf(request.getSelector().name());
            } catch (RuntimeException e) {
                throw new InvalidAmountException("invalid selector: " + request.getSelector());
            }
        }

        com.ffroliva.tinyledger.shared.Money costBasis = toMoney(request.getCostBasis());

        return new AssetTransfer(
                caller,
                callerIsAdmin,
                new AccountId(accountUid),
                transferUid,
                request.getDirection().name(),
                qty,
                costBasis,
                request.getLotId(),
                selector,
                request.getReference());
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

    static AssetTransaction toAssetTransaction(MovementResult result, AssetTransferRequest request) {
        AssetClass assetClass = AssetClass.valueOf(request.getAssetClass().name());
        Quantity qty = result.quantity() != null
                ? (result.quantity().isNegative() ? result.quantity().negated() : result.quantity())
                : Quantity.of(request.getAssetSymbol(), assetClass, request.getQuantity());

        List<TaxLotDto> lots = result.taxLots() != null
                ? result.taxLots().stream()
                        .map(lot -> new TaxLotDto(
                                lot.lotId(),
                                lot.remaining().toDecimal().toPlainString(),
                                toMoney(lot.costBasis()),
                                at(lot.acquiredAt())))
                        .toList()
                : List.of();

        return new AssetTransaction(
                        result.movementUid(),
                        result.accountId().value(),
                        AssetTransaction.TypeEnum.ASSET_TRANSFER,
                        AssetTransaction.DirectionEnum.fromValue(
                                request.getDirection().name()),
                        request.getAssetSymbol(),
                        request.getAssetClass(),
                        qty.toDecimal().toPlainString(),
                        toMoney(result.amount()),
                        AssetTransaction.StatusEnum.SETTLED,
                        at(result.occurredAt()),
                        at(result.occurredAt()))
                .taxLots(lots)
                .balanceAfter(toMoney(result.balanceAfter()))
                .reference(request.getReference());
    }

    /**
     * Spec §6.3/§6.5: a refusal is a 422 whose {@code type} is the rejection reason — and it replays as the
     * same 422, which is why {@code REJECTED_REPLAYED} lands here too.
     */
    static ErrorResponseException rejection(MovementResult result) {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "/errors/" + result.rejectionReason(),
                switch (result.rejectionReason()) {
                    case "insufficient-funds" -> "Insufficient funds";
                    case "insufficient-holding" -> "Insufficient holding";
                    case "currency-mismatch" -> "Currency mismatch";
                    case "asset-mismatch" -> "Asset mismatch";
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
