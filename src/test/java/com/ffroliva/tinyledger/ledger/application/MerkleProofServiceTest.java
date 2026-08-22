package com.ffroliva.tinyledger.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ffroliva.tinyledger.ledger.adapter.out.inmemory.InMemoryEventStore;
import com.ffroliva.tinyledger.ledger.application.error.AccountNotFoundException;
import com.ffroliva.tinyledger.ledger.application.error.OwnershipException;
import com.ffroliva.tinyledger.ledger.application.port.in.QueryMerkleProofUseCase.MerkleProof;
import com.ffroliva.tinyledger.ledger.application.usecase.MerkleProofService;
import com.ffroliva.tinyledger.ledger.domain.AccountOpened;
import com.ffroliva.tinyledger.ledger.domain.LedgerEvent;
import com.ffroliva.tinyledger.ledger.domain.MerkleTree;
import com.ffroliva.tinyledger.ledger.domain.MoneyDeposited;
import com.ffroliva.tinyledger.shared.AccountId;
import com.ffroliva.tinyledger.shared.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MerkleProofServiceTest {

    private static final Currency GBP = Currency.getInstance("GBP");
    private final InMemoryEventStore store = new InMemoryEventStore();
    private final Instant now = Instant.parse("2026-08-04T12:00:00Z");
    private final MerkleProofService service = new MerkleProofService(store);

    /**
     * One AccountOpened plus {@code deposits} deposits. Everything is a deterministic function of
     * {@code id} — including the movement uids, which the store enforces as globally unique, so
     * deriving them from a per-stream constant would collide the moment a second stream is built.
     *
     * <p>{@code editedIndex} is what makes the tamper case a controlled comparison rather than a
     * coincidence: two streams built with the same id differ in exactly one reference and in
     * nothing else.
     */
    private static List<LedgerEvent> events(AccountId id, Instant at, int deposits, int editedIndex) {
        List<LedgerEvent> events = new ArrayList<>();
        events.add(new AccountOpened(id, 1, at, "alice", "ACC-001", GBP));
        for (int i = 0; i < deposits; i++) {
            events.add(new MoneyDeposited(
                    id,
                    2L + i,
                    at.plusSeconds(i + 1),
                    UUID.nameUUIDFromBytes((id.value() + "-mv-" + i).getBytes()),
                    Money.of("GBP", 1),
                    i == editedIndex ? "EDITED" : "ref-" + i,
                    Money.of("GBP", i + 1),
                    "alice"));
        }
        return events;
    }

    private AccountId streamOf(int deposits) {
        AccountId id = AccountId.random();
        store.append(id, 0, events(id, now, deposits, -1));
        return id;
    }

    @Test
    void anUnknownAccountHasNoProof() {
        assertThatThrownBy(() -> service.merkleProof("alice", AccountId.random()))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void aNonOwnerIsRefused() {
        AccountId id = streamOf(2);

        assertThatThrownBy(() -> service.merkleProof("mallory", id)).isInstanceOf(OwnershipException.class);
    }

    @Test
    void everyEventContributesOneLeaf() {
        MerkleProof proof = service.merkleProof("alice", streamOf(4));

        assertThat(proof.leafCount()).isEqualTo(5);
        assertThat(proof.eventHashes()).hasSize(5).doesNotHaveDuplicates();
        assertThat(proof.eventHashes()).allSatisfy(hash -> assertThat(hash).hasSize(64));
        assertThat(proof.merkleRoot()).hasSize(64);
    }

    @Test
    void theProofForTheLatestEventVerifiesAtEveryStreamLength() {
        // 1..8 events, so the odd-node promotion path is covered rather than avoided. This is the
        // regression guard for the defect that shipped in MerkleTree.verify: it returned false for
        // a genuine proof on any non-power-of-two stream, and a service self-reporting
        // `verified: false` on ordinary data is an audit control nobody can use.
        for (int deposits = 0; deposits <= 7; deposits++) {
            MerkleProof proof = service.merkleProof("alice", streamOf(deposits));

            assertThat(proof.verified())
                    .as("a %d-event stream must produce a proof that verifies", deposits + 1)
                    .isTrue();
            assertThat(MerkleTree.verify(
                            proof.eventHashes().getLast(),
                            proof.latestEventProof(),
                            proof.merkleRoot(),
                            proof.leafCount() - 1,
                            proof.leafCount()))
                    .as("and it must verify for an independent caller too, not only internally")
                    .isTrue();
        }
    }

    @Test
    void aSingleEventStreamIsItsOwnRootAndNeedsNoProofPath() {
        MerkleProof proof = service.merkleProof("alice", streamOf(0));

        assertThat(proof.leafCount()).isEqualTo(1);
        assertThat(proof.merkleRoot()).isEqualTo(proof.eventHashes().getFirst());
        assertThat(proof.latestEventProof()).isEmpty();
        assertThat(proof.verified()).isTrue();
    }

    @Test
    void editingAnEarlyEventChangesEveryLaterHashAndTheRoot() {
        // The chain property, end to end through the service. Two streams identical except for the
        // FIRST deposit's reference: every hash from that point on must move, and so must the root.
        // If only the edited leaf moved, an auditor comparing roots would still catch it — but a
        // caller checking one event's hash would not, which is why the chain is threaded.
        // Same account id, same uids, same timestamps, in two separate stores — so the ONLY
        // variable is deposit 0's reference. Without holding the id fixed the hashes would differ
        // for reasons unrelated to the edit and this would pass without proving anything.
        AccountId id = AccountId.random();
        InMemoryEventStore honestStore = new InMemoryEventStore();
        InMemoryEventStore tamperedStore = new InMemoryEventStore();
        honestStore.append(id, 0, events(id, now, 3, -1));
        tamperedStore.append(id, 0, events(id, now, 3, 0));

        MerkleProof honest = new MerkleProofService(honestStore).merkleProof("alice", id);
        MerkleProof tampered = new MerkleProofService(tamperedStore).merkleProof("alice", id);

        assertThat(tampered.eventHashes().getFirst())
                .as("the untouched AccountOpened must hash identically — otherwise the comparison "
                        + "proves nothing about the edit")
                .isEqualTo(honest.eventHashes().getFirst());
        assertThat(tampered.eventHashes().subList(1, 4))
                .as("the edited event and every successor must change")
                .doesNotContainAnyElementsOf(honest.eventHashes().subList(1, 4));
        assertThat(tampered.merkleRoot()).isNotEqualTo(honest.merkleRoot());
    }

    @Test
    void theSameStreamAlwaysProducesTheSameRoot() {
        // Determinism is what lets an auditor recompute a root days later and compare. Nothing
        // timestamped or random may enter the artefact — which is why the service takes no ClockPort.
        AccountId id = streamOf(4);

        assertThat(service.merkleProof("alice", id).merkleRoot())
                .isEqualTo(service.merkleProof("alice", id).merkleRoot());
    }
}
