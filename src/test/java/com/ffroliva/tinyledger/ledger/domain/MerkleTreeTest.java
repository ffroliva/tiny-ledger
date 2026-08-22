package com.ffroliva.tinyledger.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A Merkle tree whose proofs do not verify is an audit control that reports success and proves
 * nothing, so the round-trip is asserted for EVERY leaf at EVERY size from 1 to 8 rather than for a
 * convenient one.
 *
 * <p>That range is chosen, not arbitrary. Sizes 1, 2, 4 and 8 are powers of two, where every level
 * pairs cleanly and a wrong index calculation still happens to work. Sizes 3, 5, 6 and 7 force the
 * odd-node promotion, which is the only path where {@code proof} and {@code verify} can disagree
 * about how far the index has been halved. A suite that tested only four leaves would be green
 * against a broken implementation.
 */
class MerkleTreeTest {

    private static List<String> leaves(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> ReasonTraceHash.chain(ReasonTraceHash.GENESIS_PREVIOUS, "event-" + i)
                        .value())
                .toList();
    }

    @ParameterizedTest(name = "every leaf of a {0}-leaf tree proves its own inclusion")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void everyLeafProvesItsInclusionAgainstTheRoot(int size) {
        List<String> leaves = leaves(size);
        MerkleTree tree = MerkleTree.of(leaves);

        for (int index = 0; index < size; index++) {
            List<String> proof = tree.proof(index);

            assertThat(MerkleTree.verify(leaves.get(index), proof, tree.root(), index, size))
                    .as("leaf %d of %d must verify against the root", index, size)
                    .isTrue();
        }
    }

    @ParameterizedTest(name = "a forged leaf fails verification in a {0}-leaf tree")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void aForgedLeafFailsVerification(int size) {
        // The must-fail half. Without it, a `verify` that returned true unconditionally would pass
        // every assertion above — which is precisely the shape of an audit control that proves
        // nothing while reporting success.
        List<String> leaves = leaves(size);
        MerkleTree tree = MerkleTree.of(leaves);
        String forged = ReasonTraceHash.chain(ReasonTraceHash.GENESIS_PREVIOUS, "forged")
                .value();

        for (int index = 0; index < size; index++) {
            assertThat(MerkleTree.verify(forged, tree.proof(index), tree.root(), index, size))
                    .as("a forged leaf at position %d of %d must not verify", index, size)
                    .isFalse();
        }
    }

    @Test
    void aSingleLeafIsItsOwnRoot() {
        List<String> leaf = leaves(1);

        assertThat(MerkleTree.of(leaf).root()).isEqualTo(leaf.getFirst());
    }

    @Test
    void twoLeavesHashIntoTheirPairInOrder() {
        List<String> pair = leaves(2);

        assertThat(MerkleTree.of(pair).root()).isEqualTo(MerkleTree.hashPair(pair.get(0), pair.get(1)));
    }

    @Test
    void leafOrderIsPartOfTheRoot() {
        // Swapping two events must change the root; otherwise the tree attests to a multiset and a
        // reordered ledger would pass an audit unchanged.
        List<String> ordered = leaves(4);
        List<String> swapped = new ArrayList<>(ordered);
        swapped.set(0, ordered.get(1));
        swapped.set(1, ordered.get(0));

        assertThat(MerkleTree.of(swapped).root())
                .isNotEqualTo(MerkleTree.of(ordered).root());
    }

    @ParameterizedTest(name = "altering leaf {0} changes the root")
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void alteringAnyLeafChangesTheRoot(int index) {
        // Tamper evidence is this class's stated purpose, asserted at every position because a
        // promotion path can leave one leaf out of the root's preimage entirely.
        List<String> original = leaves(5);
        List<String> tampered = new ArrayList<>(original);
        tampered.set(
                index,
                ReasonTraceHash.chain(ReasonTraceHash.GENESIS_PREVIOUS, "tampered")
                        .value());

        assertThat(MerkleTree.of(tampered).root())
                .as("editing leaf %d must be visible in the root", index)
                .isNotEqualTo(MerkleTree.of(original).root());
    }

    @Test
    void anEmptyStreamHasNoTree() {
        assertThatThrownBy(() -> MerkleTree.of(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one leaf");
    }

    @Test
    void aProofIsRefusedForALeafThatIsNotInTheTree() {
        MerkleTree tree = MerkleTree.of(leaves(3));

        assertThatThrownBy(() -> tree.proof(3)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> tree.proof(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void theLeavesAreExposedAsAnUnmodifiableCopy() {
        List<String> source = new ArrayList<>(leaves(3));
        MerkleTree tree = MerkleTree.of(source);

        source.set(0, "mutated-after-construction");

        assertThat(tree.leaves()).isNotEqualTo(source).hasSize(3);
        assertThat(tree.size()).isEqualTo(3);
        assertThatThrownBy(() -> tree.leaves().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void treesOverTheSameLeavesAreEqual() {
        assertThat(MerkleTree.of(leaves(3)))
                .isEqualTo(MerkleTree.of(leaves(3)))
                .hasSameHashCodeAs(MerkleTree.of(leaves(3)));
        assertThat(MerkleTree.of(leaves(3))).isNotEqualTo(MerkleTree.of(leaves(4)));
        assertThat(MerkleTree.of(leaves(3)).toString()).contains("root=").contains("leaves=3");
    }
}
