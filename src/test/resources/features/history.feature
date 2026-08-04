@standalone
Feature: Transaction history reads newest first

  @P4
  Scenario: alice reads history
    Given an account "ACC-001" in GBP with a balance of 100.00
    When a deposit of 25.00 is requested into "ACC-001"
    And a withdrawal of 30.00 is requested from "ACC-001"
    Then the history of "ACC-001" reads newest first
    And each history entry carries the balanceAfter its movement produced
    And the history of "ACC-001" reconciles to its balance
