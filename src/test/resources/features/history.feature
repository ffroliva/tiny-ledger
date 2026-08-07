@standalone
Feature: Transaction history reads newest first

  # Ordering caveat, borrowed rather than owned: production orders the feed on millisecond
  # granularity with a transactionUid tiebreak, so two movements inside one millisecond are ordered
  # arbitrarily. These scenarios hold because the suite's clock seam hands every event its own
  # millisecond (CucumberSpringConfig.strictlyIncreasingClock) — they assert the contract, not that
  # contention is safe. Candidate follow-up is a stream-version tiebreak in the feed's sort and
  # cursor, which is a spec/production decision and not this suite's to make.

  # P10. §7's cursor was covered only for the *audit* trail (KafkaAuditModuleIT) and only against mocks
  # for this endpoint (BalanceControllerTest, which proves the next-URL is built correctly, not that
  # following it returns the right rows). Nothing walked the transaction history's own cursor end to
  # end, which is where a page-boundary off-by-one would live: a cursor that repeats the row it resumed
  # from, skips it, or reorders across the boundary. Four movements at limit=1 crosses every boundary
  # there is.
  #
  # Red run: cursor encoded from one row further on than the page's last (an off-by-one skip) — 27 run,
  # exactly 1 failure, this scenario. No other test in the suite notices a paging cursor that loses a
  # movement, which is the gap this closes.
  #
  # Asserted against the unpaged read rather than a hand-written expected list, because the defects here
  # are relational — repeat, skip, reorder — and one comparison catches all three without needing to be
  # re-derived every time a scenario adds a movement.
  @P10
  Scenario: History pages with a cursor without losing, repeating or reordering a movement
    Given an account "ACC-001" in GBP with a balance of 100.00
    When a deposit of 10.00 is requested into "ACC-001"
    And a deposit of 20.00 is requested into "ACC-001"
    And a withdrawal of 5.00 is requested from "ACC-001"
    Then paging the history of "ACC-001" one at a time yields exactly the unpaged history
    And the history of "ACC-001" contains 4 transactions

  @P4
  Scenario: alice reads history
    Given an account "ACC-001" in GBP with a balance of 100.00
    When a deposit of 25.00 is requested into "ACC-001"
    And a withdrawal of 30.00 is requested from "ACC-001"
    Then the history of "ACC-001" reads newest first
    And each history entry carries the balanceAfter its movement produced
    And the history of "ACC-001" reconciles to its balance
