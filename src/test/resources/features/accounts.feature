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

  # N22. §6.3 makes the movement UID client-supplied and account opening server-uid'd, so a repeated
  # POST is genuinely two accounts. That is a decision with a cost — a client that retries an open it
  # never saw the response to gets a second account — and it is pinned here so it stays a decision
  # rather than becoming an accident. The CLI already relies on it: `client.py` deliberately excludes
  # POST /api/v1/accounts from its transport retries, "the one POST that has no idempotency key".
  #
  # No discriminating red run is recorded here, and that is a statement rather than an omission. The
  # slip this guards is "someone makes opening idempotent by name", but every other scenario in this
  # suite reopens "ACC-001", so any name-based dedup collapses them all and fails ~everything — proving
  # sensitivity while discriminating nothing, the same trap the N21 twin's comment describes. Unlike a
  # refusal-only test there is no degenerate implementation to worry about: two distinct UUIDs from two
  # POSTs is directly observable, and returning the same UID twice fails this on the first assertion.
  @N22
  Scenario: Opening the same name twice is two accounts, not an idempotent replay
    When an account named "ACC-DUP" in GBP is opened
    And an account named "ACC-DUP" in GBP is opened again
    Then the two opens returned different account UIDs, each at stream version 1

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
