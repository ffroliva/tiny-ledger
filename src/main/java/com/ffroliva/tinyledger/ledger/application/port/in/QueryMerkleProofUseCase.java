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
     * and an inclusion proof for the most recent event.
     */
    record MerkleProof(
            String merkleRoot,
            List<String> eventHashes,
            int leafCount,
            List<String> latestEventProof,
            boolean verified) {}
}
