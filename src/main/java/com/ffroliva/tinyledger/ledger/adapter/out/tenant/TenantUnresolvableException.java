package com.ffroliva.tinyledger.ledger.adapter.out.tenant;

import com.ffroliva.tinyledger.shared.error.ErrorCode;
import com.ffroliva.tinyledger.shared.error.TinyLedgerException;

/**
 * A tenant could not be determined for the current context.
 *
 * <p>Unchecked and deliberately not caught by any business code: there is no recovery that is safe.
 * Every alternative to failing — a default tenant, a null tenant, the last-seen tenant — binds work
 * to a tenant nobody authorised, and the seed contract makes tenant the sole isolation unit.
 *
 * <p>It surfaces as {@link ErrorCode#UNAUTHENTICATED} rather than falling into the 500 catch-all: an
 * unresolvable tenant means the presented credential is insufficient to establish who is asking, and
 * a refusal to guess is an auth failure, not a server defect. The claim name in the internal message
 * never crosses the boundary — the problem detail is built from the catalogue's message bundle.
 */
public class TenantUnresolvableException extends TinyLedgerException {

    public TenantUnresolvableException(String message) {
        super(ErrorCode.UNAUTHENTICATED, message);
    }
}
