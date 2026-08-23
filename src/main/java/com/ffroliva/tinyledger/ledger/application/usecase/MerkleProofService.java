package com.ffroliva.tinyledger.ledger.application.usecase;

import com.ffroliva.tinyledger.ledger.application.error.AccountNotFoundException;
import com.ffroliva.tinyledger.ledger.application.error.OwnershipException;
import com.ffroliva.tinyledger.ledger.application.port.in.QueryMerkleProofUseCase;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.ledger.domain.Account;
import com.ffroliva.tinyledger.ledger.domain.EventCanonicalForm;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.ledger.domain.MerkleTree;
import com.ffroliva.tinyledger.ledger.domain.ReasonTraceHash;
import com.ffroliva.tinyledger.shared.AccountId;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the tamper-evidence artefacts for one account's stream: a SHA-256 hash chain over every
 * event in order, a Merkle tree over those hashes, and an inclusion proof for the latest event.
 *
 * <p>Reads the stream directly through {@link EventStorePort#read(AccountId)} rather than the
 * balance projection, and that is the point rather than an implementation preference: a projection
 * is derived state, and a proof computed from derived state attests to the derivation, not to the
 * log. If the two ever disagree, this has to follow the log.
 *
 * <p><strong>The chain is recomputed on every call, never stored.</strong> A stored chain is a
 * second copy of the truth that an attacker who reaches the database can edit alongside the events
 * it summarises. Recomputing costs a stream read and one SHA-256 per event, and it means the root
 * this returns is derived from what the log says right now.
 *
 * <p>Authorises by ownership, matching {@code StrongBalanceService} exactly. Note what that means
 * and what it does not: an {@code ledger:auditor} is NOT admitted here, because no existing read in
 * this module admits one and widening authorisation is a §6.4 decision rather than a side effect of
 * adding a query. {@code AuditController} is where the auditor role is honoured today.
 */
public class MerkleProofService implements QueryMerkleProofUseCase {

    private final EventStorePort store;

    public MerkleProofService(EventStorePort store) {
        this.store = store;
    }

    @Override
    public MerkleProof merkleProof(String caller, AccountId accountId) {
        List<LedgerEvent> history = store.read(accountId);
        if (history.isEmpty()) throw new AccountNotFoundException(accountId);
        Account account = Account.rehydrate(history);
        if (!account.owner().equals(caller)) throw new OwnershipException(caller, accountId);

        // Each hash covers its predecessor, so the i-th entry attests to events 0..i. Editing event
        // 3 of 10 changes hashes 3..9 and therefore the root — which is the property the chain
        // exists for, and why the previous hash is threaded rather than each event hashed alone.
        List<String> eventHashes = new ArrayList<>(history.size());
        String previous = ReasonTraceHash.GENESIS_PREVIOUS;
        for (LedgerEvent event : history) {
            previous = ReasonTraceHash.chain(previous, EventCanonicalForm.of(event))
                    .value();
            eventHashes.add(previous);
        }

        MerkleTree tree = MerkleTree.of(eventHashes);
        int latest = eventHashes.size() - 1;
        List<String> latestEventProof = tree.proof(latest);

        // Self-verification, and it is deliberately not a formality. `verify` is the half of this
        // pair a caller runs independently, and it was broken for every non-power-of-two stream
        // until it was tested — a proof that does not verify at the source is a proof nobody else
        // can verify either. Returning the flag means a caller reading `verified: false` learns the
        // artefact is unusable instead of trusting a root that no path reconstructs.
        boolean verified =
                MerkleTree.verify(eventHashes.get(latest), latestEventProof, tree.root(), latest, eventHashes.size());

        return new MerkleProof(
                tree.root(),
                List.copyOf(eventHashes),
                eventHashes.size(),
                latestEventProof,
                verified,
                EventCanonicalForm.V1);
    }
}
