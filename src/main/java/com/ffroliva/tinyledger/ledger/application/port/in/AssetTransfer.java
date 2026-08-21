package com.ffroliva.tinyledger.ledger.application.port.in;

import com.ffroliva.tinyledger.ledger.domain.Quantity;
import com.ffroliva.tinyledger.ledger.domain.TaxLotSelector;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.util.UUID;

public record AssetTransfer(
        String caller,
        boolean callerIsAdmin,
        AccountId accountId,
        UUID movementUid,
        String direction,
        Quantity quantity,
        Money costBasis,
        String lotId,
        TaxLotSelector selector,
        String reference) {}
