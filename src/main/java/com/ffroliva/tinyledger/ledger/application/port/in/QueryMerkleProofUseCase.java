package com.ffroliva.tinyledger.ledger.application.port.in;

import com.ffroliva.tinyledger.shared.AccountId;
import java.util.List;

/**
 * Inbound port for querying the cryptographic Merkle proof of an account's event stream.
 * The proof enables tamper-evident verification of the full event history.
 */
public interface QueryMerkleProofUseCase {

    /**
     * Builds a Merkle tree from the chained SHA-256 hashes of every event in the account's stream
     * and returns the root, the chain of per-event hashes, and an inclusion proof for the latest
     * event.
     */
    MerkleProof merkleProof(String caller, AccountId accountId);

    /**
     * The response: the Merkle root, the per-event reason-trace hash chain, the leaf count,
     * an inclusion proof for the most recent event, and the version of the canonical form the
     * hashes were computed from.
     *
     * <p>{@code canonicalFormVersion} sits <strong>outside</strong> the hashed bytes on purpose. A
     * hash is opaque, so a verifier cannot discover from the digest which codec produced it —
     * knowing the version is a precondition for recomputing, not something recomputation reveals.
     * Putting a discriminator inside the preimage instead would also change every hash already
     * computed, which is the one thing a permanent contract may not do.
     *
     * <p>Without this field a v1 proof and a future v2 proof over the same stream are
     * indistinguishable artifacts that disagree.
     */
    record MerkleProof(
            String merkleRoot,
            List<String> eventHashes,
            int leafCount,
            List<String> latestEventProof,
            boolean verified,
            int canonicalFormVersion) {}
}
