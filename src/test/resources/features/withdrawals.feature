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

  @P3
  Scenario: alice withdraws her exact balance — the boundary is allowed
    Given an account "ACC-001" in GBP with a balance of 70.00
    When a withdrawal of 70.00 is requested
    Then the movement is accepted with 201
    And the balance of "ACC-001" is 0.00
    And a "MoneyWithdrawn" event is recorded at version 3
