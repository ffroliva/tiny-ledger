package com.flaviooliva.ledger.balance.application.port.in;

import java.util.List;

public interface QueryAccountsUseCase {
    List<AccountView> accountsOwnedBy(String owner);
}
