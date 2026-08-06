@standalone
Feature: Withdrawals respect the available balance

  @N1
  Scenario: A withdrawal larger than the balance is refused
    Given an account "ACC-001" in GBP with a balance of 50.00
    When a withdrawal of 100.00 is requested
    Then the request is refused with "insufficient-funds"
    And the balance of "ACC-001" is still 50.00
    And a "MovementRejected" event is recorded

  @P2
  Scenario: alice withdraws 30.00
    Given an account "ACC-001" in GBP with a balance of 100.00
    When a withdrawal of 30.00 is requested
    Then the movement is accepted with 201
    And the balance of "ACC-001" is 70.00
    And a "MoneyWithdrawn" event is recorded at version 3

  # The immediate replay of a refusal is already covered by the "a MovementRejected event is recorded"
  # step. What is untested is whether the stored refusal survives the balance changing underneath it:
  # an implementation that re-evaluated the command on replay instead of answering from the stored
  # event would settle this one, and the caller would be charged for a withdrawal they were told was
  # refused. Version 4 at the end is the proof the replay appended nothing of its own.
  @N21
  Scenario: A refused withdrawal stays refused after a top-up — a rejection is durable
    Given an account "ACC-001" in GBP with a balance of 50.00
    When a withdrawal of 100.00 is requested
    Then the request is refused with "insufficient-funds"
    When "ACC-001" is topped up by 100.00
    And the same withdrawal PUT is retried
    Then the request is refused with "insufficient-funds"
    And the balance of "ACC-001" is 150.00
    And the stream version of "ACC-001" is 4

  # The positive twin of the scenario above, and it is not decoration. Everything above is a refusal,
  # so an implementation that permanently soured the account after any rejection would satisfy it —
  # as would a dedup key of (account, type, amount) rather than the uid, under which this same
  # 100.00 could never be withdrawn again while N21 stayed green. A retry asks "did my earlier
  # request go through"; a fresh uid says "take 100.00 now". Only the client knows which it means,
  # which is why §6.3 has the client generate the uid.
  #
  # Red run, per AGENTS trap 4 — and the discriminating one, not just any failure. Dedup keyed on
  # (account, type, amount) instead of the uid: 23 run, exactly 1 failure, this scenario. The
  # rejection-is-durable scenario above stayed green, so this is the only test in the repository
  # that can see that slip. An earlier, coarser mutation (replay any stored rejection) failed 15
  # scenarios including both of these, which proves sensitivity but discriminates nothing — it is
  # recorded here because "it went red" is not the same claim as "it went red for this reason".
  @N21
  Scenario: A fresh uid after the top-up is a new intent, and it settles
    Given an account "ACC-001" in GBP with a balance of 50.00
    When a withdrawal of 100.00 is requested
    Then the request is refused with "insufficient-funds"
    When "ACC-001" is topped up by 100.00
    And a withdrawal of 100.00 is requested
    Then the movement is accepted with 201
    And the balance of "ACC-001" is 50.00
    And a "MoneyWithdrawn" event is recorded at version 5

  @P3
  Scenario: alice withdraws her exact balance — the boundary is allowed
    Given an account "ACC-001" in GBP with a balance of 70.00
    When a withdrawal of 70.00 is requested
    Then the movement is accepted with 201
    And the balance of "ACC-001" is 0.00
    And a "MoneyWithdrawn" event is recorded at version 3
