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

  @N11
  Scenario: A reused deposit UID with a different amount is a conflict
    Given an account "ACC-001" in GBP
    When a deposit of 100.00 is requested into "ACC-001"
    And the same deposit PUT is retried with an amount of 250.00
    Then the request is rejected with 409 "/errors/idempotency-conflict"
    And the original movement stands untouched at 100.00
    And the stream version of "ACC-001" is 2
