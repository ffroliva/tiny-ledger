package com.flaviooliva.ledger.config;

import com.flaviooliva.ledger.ledger.application.port.in.Deposit;
import com.flaviooliva.ledger.ledger.application.port.in.MovementResult;
import com.flaviooliva.ledger.ledger.application.port.in.OpenAccount;
import com.flaviooliva.ledger.ledger.application.port.in.OpenAccountUseCase;
import com.flaviooliva.ledger.ledger.application.port.in.OpenedAccount;
import com.flaviooliva.ledger.ledger.application.port.in.RecordMovementUseCase;
import com.flaviooliva.ledger.ledger.application.port.in.Withdraw;
import com.flaviooliva.ledger.ledger.application.usecase.OpenAccountService;
import com.flaviooliva.ledger.ledger.application.usecase.RecordMovementService;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR 0001: the event append and the event publication must share one transaction, or Modulith
 * writes its publication row outside the transaction that wrote the event and the dual-write
 * window reopens. The boundary lives here rather than on the services because {@code application}
 * carries no framework annotations (ArchUnit) — and a transaction is an infrastructure concern
 * applied at a port boundary anyway.
 */
final class TransactionalUseCases {

    private TransactionalUseCases() {}

    static class Opening implements OpenAccountUseCase {
        private final OpenAccountService delegate;

        Opening(OpenAccountService delegate) {
            this.delegate = delegate;
        }

        @Override
        @Transactional
        public OpenedAccount open(OpenAccount cmd) {
            return delegate.open(cmd);
        }
    }

    static class Movements implements RecordMovementUseCase {
        private final RecordMovementService delegate;

        Movements(RecordMovementService delegate) {
            this.delegate = delegate;
        }

        @Override
        @Transactional
        public MovementResult deposit(Deposit cmd) {
            return delegate.deposit(cmd);
        }

        @Override
        @Transactional
        public MovementResult withdraw(Withdraw cmd) {
            return delegate.withdraw(cmd);
        }
    }
}
