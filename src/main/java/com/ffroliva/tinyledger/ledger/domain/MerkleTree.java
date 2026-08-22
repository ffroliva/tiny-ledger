package com.ffroliva.tinyledger.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * A binary Merkle tree built over a list of leaf hashes (event reason-trace hashes). The root
 * summarises the entire event stream into a single digest that changes if any leaf is altered,
 * enabling O(log n) tamper proofs for fiduciary audits.
 *
 * <p>Framework-free: only JDK types, per {@code HexagonalRulesTest}'s domain rule.
 */
public final class MerkleTree {

    private final String root;
    private final List<String> leaves;

    private MerkleTree(String root, List<String> leaves) {
        this.root = Objects.requireNonNull(root, "root");
        this.leaves = List.copyOf(leaves);
    }

    /**
     * Builds a Merkle tree from the given leaf hashes.
     *
     * @param leafHashes hex-encoded SHA-256 hashes of the events — one per event
     * @return the tree with a computed root
     * @throws IllegalArgumentException if the list is empty
     */
    public static MerkleTree of(List<String> leafHashes) {
        if (leafHashes.isEmpty()) {
            throw new IllegalArgumentException("at least one leaf is required");
        }
        List<String> leaves = List.copyOf(leafHashes);
        String computedRoot = computeRoot(leaves);
        return new MerkleTree(computedRoot, leaves);
    }

    /** The root digest — changes if any leaf changes. */
    public String root() {
        return root;
    }

    /** An unmodifiable copy of the leaf hashes used to build this tree. */
    public List<String> leaves() {
        return leaves;
    }

    /** The number of leaves (events) in the tree. */
    public int size() {
        return leaves.size();
    }

    /**
     * Produces an inclusion proof (audit path) for the leaf at the given index. The proof is a list
     * of sibling hashes from leaf to root that, together with the leaf, reconstruct the root.
     */
    public List<String> proof(int leafIndex) {
        if (leafIndex < 0 || leafIndex >= leaves.size()) {
            throw new IndexOutOfBoundsException("leafIndex " + leafIndex + " out of range [0, " + leaves.size() + ")");
        }
        List<String> siblings = new ArrayList<>();
        List<String> level = new ArrayList<>(leaves);
        int index = leafIndex;

        while (level.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < level.size(); i += 2) {
                if (i + 1 < level.size()) {
                    if (i == index || i + 1 == index) {
                        siblings.add(i == index ? level.get(i + 1) : level.get(i));
                    }
                    next.add(hashPair(level.get(i), level.get(i + 1)));
                } else {
                    // Odd leaf is promoted — if it is the target index, no sibling is needed.
                    next.add(level.get(i));
                }
            }
            index = index / 2;
            level = next;
        }
        return List.copyOf(siblings);
    }

    /**
     * Verifies that a given leaf hash, combined with the proof path, reconstructs the expected root.
     */
    public static boolean verify(
            String leafHash, List<String> proofPath, String expectedRoot, int leafIndex, int treeSize) {
        if (leafIndex < 0 || treeSize < 1 || leafIndex >= treeSize) {
            return false;
        }
        String current = leafHash;
        int index = leafIndex;
        int size = treeSize;
        int consumed = 0;

        // WALK LEVELS, NOT PROOF ELEMENTS, and that distinction is the whole correctness argument.
        // A previous version iterated `proofPath` and halved `index` once per sibling. That silently
        // assumed every level contributes a sibling, which is false: `computeRoot` PROMOTES a lone
        // odd node instead of pairing it, so that level emits no sibling while still halving the
        // index. The two then disagreed about how far the index had been halved, the parity flipped,
        // and `hashPair` was called with its operands the wrong way round — so a genuine proof for a
        // genuine leaf returned false. It only showed up when a promotion sat below the level where
        // a sibling was taken, which is why every power-of-two tree looked fine: sizes 1, 2, 4 and 8
        // never promote. Sizes 3, 5, 6 and 7 do, and MerkleTreeTest now covers each of them.
        //
        // `treeSize` exists for exactly this and was previously computed into `size` and never read.
        while (size > 1) {
            boolean promoted = index == size - 1 && size % 2 == 1;
            if (!promoted) {
                if (consumed >= proofPath.size()) {
                    return false; // the path is shorter than the tree's depth — not a valid proof
                }
                String sibling = proofPath.get(consumed++);
                current = index % 2 == 0 ? hashPair(current, sibling) : hashPair(sibling, current);
            }
            index = index / 2;
            size = (size + 1) / 2;
        }
        // A path with elements left over describes a different tree than the one claimed, so it is
        // refused rather than ignored — an over-long proof must not verify by accident.
        return consumed == proofPath.size() && current.equals(expectedRoot);
    }

    private static String computeRoot(List<String> hashes) {
        List<String> level = new ArrayList<>(hashes);
        while (level.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < level.size(); i += 2) {
                if (i + 1 < level.size()) {
                    next.add(hashPair(level.get(i), level.get(i + 1)));
                } else {
                    next.add(level.get(i)); // odd node promoted
                }
            }
            level = next;
        }
        return level.getFirst();
    }

    static String hashPair(String left, String right) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((left + right).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof MerkleTree that && root.equals(that.root) && leaves.equals(that.leaves));
    }

    @Override
    public int hashCode() {
        return Objects.hash(root, leaves);
    }

    @Override
    public String toString() {
        return "MerkleTree[root=" + root + ", leaves=" + leaves.size() + "]";
    }
}
