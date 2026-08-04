package com.ffroliva.tinyledger.ledger.application.port.in;

public interface RecordMovementUseCase {
    MovementResult deposit(Deposit cmd);

    MovementResult withdraw(Withdraw cmd);
}
