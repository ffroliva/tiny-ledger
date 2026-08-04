@standalone
Feature: Large movements produce a notification record

  # The threshold is ledger.notification.large-movement-minor-units = 1000000 (10 000.00, spec §3).

  @P8
  Scenario: alice deposits 15000.00, and then 20.00
    Given an account "ACC-001" in GBP
    When a deposit of 15000.00 is requested into "ACC-001"
    Then a "LARGE_MOVEMENT" notification carrying the movement UID is produced
    When a deposit of 20.00 is requested into "ACC-001"
    Then no notification is produced for that movement
