@standalone
Feature: Transaction history reads newest first

  # Ordering caveat, borrowed rather than owned: production orders the feed on millisecond
  # granularity with a transactionUid tiebreak, so two movements inside one millisecond are ordered
  # arbitrarily. These scenarios hold because the suite's clock seam hands every event its own
  # millisecond (CucumberSpringConfig.strictlyIncreasingClock) — they assert the contract, not that
  # contention is safe. Candidate follow-up is a stream-version tiebreak in the feed's sort and
  # cursor, which is a spec/production decision and not this suite's to make.

  @P4
  Scenario: alice reads history
    Given an account "ACC-001" in GBP with a balance of 100.00
    When a deposit of 25.00 is requested into "ACC-001"
    And a withdrawal of 30.00 is requested from "ACC-001"
    Then the history of "ACC-001" reads newest first
    And each history entry carries the balanceAfter its movement produced
    And the history of "ACC-001" reconciles to its balance
