package com.flaviooliva.ledger.ledger.application.port.in;

import com.flaviooliva.ledger.shared.AccountId;

public record OpenedAccount(AccountId accountId, long version) {}
