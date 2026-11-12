package com.ffroliva.tinyledger.ledger.application.port;

import static org.assertj.core.api.Assertions.assertThat;

import com.ffroliva.tinyledger.ledger.application.port.in.MovementResult;
import com.ffroliva.tinyledger.ledger.application.port.in.QueryMerkleProofUseCase.MerkleProof;
import com.ffroliva.tinyledger.ledger.domain.AssetTransferred;
import com.ffroliva.tinyledger.ledger.domain.TaxLot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Records that carry a List keep their own copy (SpotBugs EI_EXPOSE_REP/REP2): a caller that mutates the list it
 * passed in, or the list it got back, must not change a proof, an event or a result after the fact. Absent lists stay
 * absent — cash movements carry no tax lots, and the web mapper relies on null meaning "none".
 */
class RecordListImmutabilityTest {

    @Test
    void aMerkleProofIsNotChangedByMutatingTheListsItWasBuiltFrom() {
        List<String> hashes = new ArrayList<>(List.of("h1", "h2"));
        List<String> path = new ArrayList<>(List.of("p1"));
        MerkleProof proof = new MerkleProof("root", hashes, 2, path, true, 1);

        hashes.add("forged");
        path.clear();

        assertThat(proof.eventHashes()).containsExactly("h1", "h2");
        assertThat(proof.latestEventProof()).containsExactly("p1");
    }

    @Test // a legacy stored payload deserialises without taxLots: the copy must not turn absent into a crash
    void anEventWithoutTaxLotsStillLoads() {
        AssetTransferred legacy = new AssetTransferred(null, 1, null, null, null, null, null, null, null, null, null);

        assertThat(legacy.taxLots()).isNull();
    }

    @Test // the same absent list then flows into the result built from that event (RecordMovementService)
    void aResultFromALegacyEventStillHasNoTaxLots() {
        MovementResult result = new MovementResult(null, null, null, 1, null, null, null, null, null, null, null);

        assertThat(result.taxLots()).isNull();
    }

    @Test
    void aMovementResultIsNotChangedByMutatingItsTaxLots() {
        List<TaxLot> lots = new ArrayList<>();
        MovementResult result = new MovementResult(null, null, null, 1, null, null, null, null, null, null, lots);

        lots.add(null);

        assertThat(result.taxLots()).isEmpty();
    }
}
