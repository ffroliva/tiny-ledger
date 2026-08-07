@standalone
Feature: A movement UID is the movement's permanent identity

  @P6
  Scenario: alice retries the same deposit PUT
    Given an account "ACC-001" in GBP
    When a deposit of 100.00 is requested into "ACC-001"
    And the same deposit PUT is retried
    Then the retry is 200 with a body identical to the original
    And the balance of "ACC-001" is 100.00
    And the history of "ACC-001" contains 1 transactions
    And the stream version of "ACC-001" is 2

  # N20. §6.3 says the UID lookup is global, matching the unique index — not scoped to one stream. The
  # difference is only observable across accounts, and nothing tested it: a per-stream lookup would
  # satisfy P6 and N11 completely, and would then credit this second account as a fresh movement while
  # the ledger believed the UID was already spent.
  #
  # This is also the one path where RecordMovementService's DuplicateMovementException catch is
  # reachable in a real race — the version check that shadows it for same-stream racers (§6.3, N19)
  # passes here, because this is a different stream and the global unique index is what fires.
  #
  # Red run, and it took TWO mutations, which is the finding. Making the service's lookup per-stream
  # alone leaves all 25 scenarios green: the store's global unique index still fires on append and the
  # catch at RecordMovementService:73 re-reads by UID, finds the other account's event, and answers the
  # same 409. Only with the store's uniqueness ALSO scoped per stream — a coherent "per-stream
  # idempotency" design — does this go red, 25 run and exactly 1 failure, `expected: 409 but was: 201`.
  # A 201 there means the second account was credited as a fresh movement. This is the concrete
  # confirmation of performance-findings §6.7: idempotency is enforced twice, and this is the one case
  # where the second mechanism is load-bearing rather than redundant.
  @N20
  Scenario: A movement UID reused against a different account is a conflict
    Given an account "ACC-001" in GBP
    And an account "ACC-002" in GBP
    When a deposit of 100.00 is requested into "ACC-001"
    And the same deposit UID is reused against "ACC-002"
    Then the request is rejected with 409 "/errors/idempotency-conflict"
    And the stream version of "ACC-002" is 1
    And the balance of "ACC-001" is 100.00
    And the stream version of "ACC-001" is 2

  @N11
  Scenario: A reused deposit UID with a different amount is a conflict
    Given an account "ACC-001" in GBP
    When a deposit of 100.00 is requested into "ACC-001"
    And the same deposit PUT is retried with an amount of 250.00
    Then the request is rejected with 409 "/errors/idempotency-conflict"
    And the original movement stands untouched at 100.00
    And the stream version of "ACC-001" is 2
