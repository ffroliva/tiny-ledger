@standalone
Feature: The read side lags the write side by design

  # Standalone delivers events synchronously (spec v3.5, §4.3), so the lag has to be produced
  # deliberately: the balance listener is held at a gate and the rows observe the window rather
  # than racing it. Convergence is Awaitility with a stated timeout — never a sleep (§9.3 method).

  @E1
  Scenario: The stale window exists
    Given an account "ACC-001" in GBP with a balance of 10.00
    And the balance listener is paused
    When a deposit of 100.00 is requested into "ACC-001"
    Then the movement is accepted with 201
    And the projected balance of "ACC-001" is still 10.00
    And the projected balance of "ACC-001" carries an asOf and a streamVersion behind the aggregate's

  @E2
  Scenario: Convergence
    Given an account "ACC-001" in GBP with a balance of 10.00
    And the balance listener is paused
    When a deposit of 100.00 is requested into "ACC-001"
    Then the projected balance of "ACC-001" is still 10.00
    When the balance listener is resumed
    Then the projected balance of "ACC-001" converges to 110.00 within 5 seconds

  @E3
  Scenario: Read-your-writes escape hatch
    Given an account "ACC-001" in GBP with a balance of 10.00
    And the balance listener is paused
    When a deposit of 100.00 is requested into "ACC-001"
    Then the projected balance of "ACC-001" is still 10.00
    And the strong balance of "ACC-001" is 110.00

  @E4
  Scenario: Duplicate delivery is harmless
    Given an account "ACC-001" in GBP with a balance of 10.00
    And the balance listener is paused
    When a deposit of 100.00 is requested into "ACC-001"
    And every held event is delivered twice
    Then the balance of "ACC-001" is 110.00
    And the history of "ACC-001" contains 2 transactions

  @E5
  Scenario: Out-of-order delivery is not applied
    Given an account "ACC-001" in GBP with a balance of 10.00
    And the balance listener is paused
    When a deposit of 30.00 is requested into "ACC-001"
    And a deposit of 7.00 is requested into "ACC-001"
    And only the newest held event is delivered
    Then the projected balance of "ACC-001" is still 10.00
    When the held events are delivered in order
    Then the balance of "ACC-001" is 47.00

  @E8
  Scenario: Full rebuild from the log
    Given an account "ACC-001" in GBP with a balance of 100.00
    When a deposit of 25.00 is requested into "ACC-001"
    And a withdrawal of 30.00 is requested from "ACC-001"
    Then replaying the stream of "ACC-001" into an empty projection reproduces the projection exactly
