package com.ffroliva.tinyledger.ledger.adapter.out.tenant;

/**
 * A tenant could not be determined for the current context.
 *
 * <p>Unchecked and deliberately not caught anywhere: there is no recovery that is safe. Every
 * alternative to failing — a default tenant, a null tenant, the last-seen tenant — binds work to a
 * tenant nobody authorised, and the seed contract makes tenant the sole isolation unit.
 */
public class TenantUnresolvableException extends RuntimeException {

    public TenantUnresolvableException(String message) {
        super(message);
    }
}
