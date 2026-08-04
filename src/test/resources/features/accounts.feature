@standalone
Feature: Accounts open with an owner and keep independent streams

  # Standalone runs without auth (spec §1): every caller is the fixed principal "local", so the
  # catalogue's alice and bob read here as two independent accounts rather than two subjects.

  @P0
  Scenario: alice opens an account
    When an account named "ACC-001" in GBP is opened
    Then the open response is 201 with a Location for the new account
    And the stream version of "ACC-001" is 1
    And the account resource for "ACC-001" reports name "ACC-001", currency GBP and owner "local"

  @P5
  Scenario: bob deposits into ACC-002 while alice transacts
    Given an account "ACC-001" in GBP with a balance of 100.00
    And an account "ACC-002" in GBP with a balance of 40.00
    When a deposit of 25.00 is requested into "ACC-002"
    And a withdrawal of 10.00 is requested from "ACC-001"
    Then the balance of "ACC-001" is 90.00
    And the balance of "ACC-002" is 65.00
    And the stream version of "ACC-001" is 3
    And the stream version of "ACC-002" is 3
