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

  @P3
  Scenario: alice withdraws her exact balance — the boundary is allowed
    Given an account "ACC-001" in GBP with a balance of 70.00
    When a withdrawal of 70.00 is requested
    Then the movement is accepted with 201
    And the balance of "ACC-001" is 0.00
    And a "MoneyWithdrawn" event is recorded at version 3
