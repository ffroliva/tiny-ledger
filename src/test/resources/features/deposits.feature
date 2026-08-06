@standalone
Feature: Deposits credit the account

  @P1
  Scenario: alice deposits 100.00 into ACC-001
    Given an account "ACC-001" in GBP
    When a deposit of 100.00 is requested into "ACC-001"
    Then the movement is accepted with 201
    And the balance of "ACC-001" is 100.00
    And a "MoneyDeposited" event is recorded at version 2

  @N3
  Scenario: Two writers race on the same aggregate from the same stream version
    Given an account "ACC-001" in GBP with a balance of 100.00
    When two deposits of 10.00 race on "ACC-001" from the same stream version
    Then exactly one racing deposit is 201 and the other is 409 "/errors/version-conflict"
    And retrying the losing deposit is accepted with 201
    And the balance of "ACC-001" is 120.00
    And the stream version of "ACC-001" is 4

  @N4
  Scenario Outline: A deposit whose minorUnits is not a positive integer is malformed
    Given an account "ACC-001" in GBP
    When a deposit with a raw minorUnits value of <minorUnits> is requested into "ACC-001"
    Then the request is rejected with 400 "/errors/invalid-amount"
    And nothing is appended to the stream of "ACC-001"

    Examples:
      | minorUnits |
      | 0          |
      | -100       |

  # §6.5's invalid-amount row also covers a non-integer minorUnits shape.
  @N4
  Scenario: A deposit whose minorUnits is a non-integer is malformed
    Given an account "ACC-001" in GBP
    When a deposit with a raw minorUnits value of 100.5 is requested into "ACC-001"
    Then the request is rejected with 400 "/errors/invalid-amount"
    And nothing is appended to the stream of "ACC-001"

  # N23, the case the battle-testing plan carried as "V3" before it had a catalogue id — it needed one,
  # because the traceability sweep matches P/N/E and a @V3 tag was invisible to it.
  #
  # `minorUnits` is an int64 with `minimum: 1` in the contract, so this value is *well-formed* —
  # bean validation passes it and the OpenAPI schema admits it. It is only unrepresentable once added
  # to an existing balance, where Money.plus's Math.addExact throws ArithmeticException. That is not a
  # TinyLedgerException and not an ErrorResponse, so ErrorHandlingAdvice's catch-all claims it.
  #
  # The question this pins is whether a client can reach a 500 with input the contract permits. §6.5
  # reserves 500 for "a genuine surprise"; an amount the ledger cannot represent is a refusal it can
  # foresee, so it belongs in the catalogue rather than in the unhandled bucket.
  @N23
  Scenario: A deposit that would overflow the balance is refused, not a server error
    Given an account "ACC-001" in GBP with a balance of 50.00
    When a deposit with a raw minorUnits value of 9223372036854775807 is requested into "ACC-001"
    Then the request is rejected with 400 "/errors/invalid-amount"
    And nothing is appended to the stream of "ACC-001"

  @N5
  Scenario: A movement in a currency the account does not hold is refused
    Given an account "ACC-001" in GBP with a balance of 50.00
    When a deposit of 10.00 in EUR is requested into "ACC-001"
    Then the request is refused with "currency-mismatch"
    And the balance of "ACC-001" is still 50.00
    And a "MovementRejected" event is recorded
