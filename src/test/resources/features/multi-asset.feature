@standalone
Feature: Multi-Asset ETF transfers and tax-lot allocations

  @P10
  Scenario: Inbound ETF asset transfer records acquisition with cost-basis and tax lot
    Given an account "ACC-001" in USD
    When an asset transfer of 10.500000 "VOO" of class "EQUITY_ETF" with cost basis 4500.00 is requested into "ACC-001"
    Then the movement is accepted with 201
    And a "AssetTransferred" event is recorded at version 2

  @P11
  Scenario: Matched fractional ETF asset transfers conserve exact balance (+10.500000 VOO == -10.500000 VOO)
    Given an account "ACC-001" in USD
    When an asset transfer of 10.500000 "VOO" of class "EQUITY_ETF" with cost basis 4500.00 is requested into "ACC-001"
    Then the movement is accepted with 201
    When an outbound asset transfer of 10.500000 "VOO" of class "EQUITY_ETF" is requested from "ACC-001" using "HIFO"
    Then the movement is accepted with 201
    And the total quantity of "VOO" held in "ACC-001" is 0.000000

  @P12
  Scenario: HIFO tax-lot selection consumes highest unit cost lots first
    Given an account "ACC-001" in USD
    When an asset transfer of 10.000000 "VOO" of class "EQUITY_ETF" with cost basis 4000.00 is requested into "ACC-001" with lotId "lot-cheap"
    And an asset transfer of 10.000000 "VOO" of class "EQUITY_ETF" with cost basis 5000.00 is requested into "ACC-001" with lotId "lot-expensive"
    And an outbound asset transfer of 5.000000 "VOO" of class "EQUITY_ETF" is requested from "ACC-001" using "HIFO"
    Then the movement is accepted with 201
    And the consumed tax lot is "lot-expensive" with quantity 5.000000 and cost basis 2500.00

  @N30
  Scenario: Outbound asset transfer exceeding held quantity is rejected with 422
    Given an account "ACC-001" in USD
    When an asset transfer of 5.000000 "VOO" of class "EQUITY_ETF" with cost basis 2000.00 is requested into "ACC-001"
    And an outbound asset transfer of 10.000000 "VOO" of class "EQUITY_ETF" is requested from "ACC-001" using "HIFO"
    Then the request is refused with "insufficient-holding"
    And a "MovementRejected" event is recorded

  @N31
  Scenario: Asset transfer with more than six decimal places is rejected with 400
    Given an account "ACC-001" in USD
    When an asset transfer with raw quantity "10.5000001" of "VOO" of class "EQUITY_ETF" is requested into "ACC-001"
    Then the request is rejected with 400 "/errors/invalid-amount"
    And nothing is appended to the stream of "ACC-001"
