package com.ffroliva.tinyledger.cucumber;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ffroliva.tinyledger.balance.adapter.out.inmemory.InMemoryBalanceProjection;
import com.ffroliva.tinyledger.balance.application.port.in.HistoryQuery;
import com.ffroliva.tinyledger.balance.application.port.out.BalanceProjectionPort;
import com.ffroliva.tinyledger.ledger.application.port.out.EventStorePort;
import com.ffroliva.tinyledger.shared.AccountId;
import com.jayway.jsonpath.JsonPath;
import io.cucumber.java.After;
import io.cucumber.java.ParameterType;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Spec §9.3: every step drives the HTTP API, so the specification never depends on the design.
 *
 * <p>The catalogue names {@code alice} and {@code bob}; standalone has no auth (§1) and every caller is
 * the fixed principal {@code local}, so those rows read here as independent accounts rather than
 * independent subjects — the assertions they carry are unchanged.
 */
public class LedgerSteps {

    @LocalServerPort
    private int port;

    private RestClient http;

    private final Map<String, UUID> accounts = new HashMap<>();
    private final Map<String, List<UUID>> settledUids = new HashMap<>();
    private String currentAccount;
    private ResponseEntity<String> lastResponse;
    private ResponseEntity<String> originalResponse;
    private String lastMovementPath;
    private String lastMovementBody;
    private UUID lastMovementUid;
    private long versionBefore;
    private long amountBefore;

    private final List<UUID> raceUids = List.of(UUID.randomUUID(), UUID.randomUUID());
    private List<ResponseEntity<String>> raceResponses = List.of();
    private String raceBody;
    private UUID loserUid;

    @Autowired
    private CucumberSpringConfig.RacingEventStore racingStore;

    @Autowired
    private CucumberSpringConfig.RecordedNotifications notifications;

    @Autowired
    private PausableListenerGate gate;

    @Autowired
    private EventStorePort eventStore;

    @Autowired
    private BalanceProjectionPort liveProjection;

    /** The context is shared by every scenario, so no seam may stay armed past the one that armed it. */
    @After
    public void disarmTheSeams() {
        racingStore.disarm();
        gate.resume();
    }

    private RestClient http() {
        if (http == null) {
            http = RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + port)
                    // the catalogue asserts on statuses, so a 4xx is an answer to inspect, never a throw
                    .defaultStatusHandler(status -> true, (request, response) -> {})
                    .build();
        }
        return http;
    }

    /** {@code 50.00} in the feature file is 5000 minor units on the wire (§7). */
    @ParameterType("\\d+\\.\\d{2}")
    public long money(String value) {
        return new BigDecimal(value).movePointRight(2).longValueExact();
    }

    // ---------------------------------------------------------------- given

    @Given("an account {string} in {word} with a balance of {money}")
    public void anAccountWithABalance(String name, String currency, long minorUnits) {
        anAccount(name, currency);
        assertThat(deposit(name, currency, minorUnits, UUID.randomUUID())
                        .getStatusCode()
                        .value())
                .isEqualTo(201);
    }

    @Given("an account {string} in {word}")
    public void anAccount(String name, String currency) {
        ResponseEntity<String> opened = open(name, currency);
        assertThat(opened.getStatusCode().value()).isEqualTo(201);
    }

    // ----------------------------------------------------------------- when

    @Given("the balance listener is paused")
    public void theBalanceListenerIsPaused() {
        gate.pause();
    }

    @When("the balance listener is resumed")
    public void theBalanceListenerIsResumed() {
        gate.resume();
    }

    @When("every held event is delivered twice")
    public void everyHeldEventIsDeliveredTwice() {
        gate.deliver(gate.heldEvents());
        gate.deliver(gate.heldEvents());
    }

    /** §9.3 E5: version n+2 offered before n+1 — the projection must not fold it in yet. */
    @When("only the newest held event is delivered")
    public void onlyTheNewestHeldEventIsDelivered() {
        gate.deliver(List.of(gate.heldEvents().getLast()));
    }

    @When("the held events are delivered in order")
    public void theHeldEventsAreDeliveredInOrder() {
        gate.resume();
    }

    @When("an account named {string} in {word} is opened")
    public void anAccountNamedIsOpened(String name, String currency) {
        open(name, currency);
    }

    @When("a deposit of {money} is requested into {string}")
    public void aDepositIsRequestedInto(long minorUnits, String name) {
        aDepositInCurrencyIsRequestedInto(minorUnits, currencyOf(name), name);
    }

    @When("a deposit of {money} in {word} is requested into {string}")
    public void aDepositInCurrencyIsRequestedInto(long minorUnits, String currency, String name) {
        captureBefore(name);
        deposit(name, currency, minorUnits, UUID.randomUUID());
    }

    @When("a deposit with a raw minorUnits value of {word} is requested into {string}")
    public void aDepositWithARawMinorUnitsValue(String minorUnits, String name) {
        captureBefore(name);
        lastMovementUid = UUID.randomUUID();
        lastMovementPath = "/api/v1/accounts/%s/deposits/%s".formatted(uid(name), lastMovementUid);
        lastMovementBody = """
                {"amount":{"currency":"GBP","minorUnits":%s}}""".formatted(minorUnits);
        currentAccount = name;
        lastResponse = put(lastMovementPath, lastMovementBody);
    }

    /**
     * §9.3 N3: both entrants read the same stream version (the barrier in {@link
     * CucumberSpringConfig.RacingEventStore} guarantees it), then both append. Two distinct movement UIDs —
     * the same UID would be idempotency, not concurrency (§6.3).
     */
    @When("two deposits of {money} race on {string} from the same stream version")
    public void twoDepositsRace(long minorUnits, String name) {
        String currency = currencyOf(name);
        currentAccount = name;
        raceBody = amountBody(currency, minorUnits);
        racingStore.armRace(2);
        try (ExecutorService entrants = Executors.newFixedThreadPool(2)) {
            raceResponses =
                    raceUids.stream()
                            .map(uid -> entrants.submit(() -> put(depositPath(name, uid), raceBody)))
                            .toList()
                            .stream()
                            .map(LedgerSteps::join)
                            .toList();
        } finally {
            racingStore.disarm(); // armed for these three lines only; @After is the backstop
        }
    }

    @When("the same deposit PUT is retried")
    public void theSameDepositPutIsRetried() {
        originalResponse = lastResponse;
        lastResponse = put(lastMovementPath, lastMovementBody);
    }

    @When("the same deposit PUT is retried with an amount of {money}")
    public void theSameDepositPutIsRetriedWith(long minorUnits) {
        originalResponse = lastResponse;
        lastResponse = put(lastMovementPath, amountBody(currencyOf(currentAccount), minorUnits));
    }

    @When("a withdrawal of {money} is requested")
    public void aWithdrawalIsRequested(long minorUnits) {
        aWithdrawalIsRequestedFrom(minorUnits, currentAccount);
    }

    @When("a withdrawal of {money} is requested from {string}")
    public void aWithdrawalIsRequestedFrom(long minorUnits, String name) {
        captureBefore(name);
        withdraw(name, currencyOf(name), minorUnits, UUID.randomUUID());
    }

    // ----------------------------------------------------------------- then

    @Then("the movement is accepted with {int}")
    public void theMovementIsAcceptedWith(int status) {
        assertThat(lastResponse.getStatusCode().value()).isEqualTo(status);
        assertThat(lastResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(text(lastResponse, "$.transactionUid")).isEqualTo(lastMovementUid.toString());
        assertThat(text(lastResponse, "$.status")).isEqualTo("SETTLED");
    }

    /**
     * The event name is the movement's public shape: {@code MoneyDeposited} is an {@code IN} deposit,
     * {@code MoneyWithdrawn} an {@code OUT} withdrawal. "Recorded at version n" is the strong read's
     * {@code streamVersion} (§7) — the aggregate, not the projection — plus the movement reaching the feed.
     */
    @Then("a {string} event is recorded at version {int}")
    public void anEventIsRecordedAtVersion(String eventType, int version) {
        String[] shape =
                switch (eventType) {
                    case "MoneyDeposited" -> new String[] {"DEPOSIT", "IN"};
                    case "MoneyWithdrawn" -> new String[] {"WITHDRAWAL", "OUT"};
                    default -> throw new IllegalArgumentException("unsupported event type: " + eventType);
                };
        assertThat(text(lastResponse, "$.type")).isEqualTo(shape[0]);
        assertThat(text(lastResponse, "$.direction")).isEqualTo(shape[1]);
        assertThat(number(get(strongBalancePath(currentAccount)), "$.streamVersion"))
                .isEqualTo(version);
        assertThat(transactionUids(currentAccount)).contains(lastMovementUid.toString());
    }

    @Then("the open response is 201 with a Location for the new account")
    public void theOpenResponseIsCreated() {
        assertThat(lastResponse.getStatusCode().value()).isEqualTo(201);
        assertThat(lastResponse.getHeaders().getLocation())
                .hasToString("/api/v1/accounts/" + text(lastResponse, "$.accountUid"));
    }

    /**
     * §9.3 P0: the accounts projection is a read model like any other, so the resource is awaited rather
     * than assumed — Awaitility with a stated timeout, never a sleep (§9.3 method).
     */
    @Then("the account resource for {string} reports name {string}, currency {word} and owner {string}")
    public void theAccountResourceReports(String name, String expectedName, String currency, String owner) {
        String location = "/api/v1/accounts/" + uid(name);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            ResponseEntity<String> account = get(location);
            assertThat(account.getStatusCode().value()).isEqualTo(200);
            assertThat(text(account, "$.name")).isEqualTo(expectedName);
            assertThat(text(account, "$.currency")).isEqualTo(currency);
            assertThat(text(account, "$.owner")).isEqualTo(owner);
            assertThat(OffsetDateTime.parse(text(account, "$.createdAt"))).isNotNull();
        });
    }

    @Then("exactly one racing deposit is 201 and the other is 409 {string}")
    public void exactlyOneRacingDepositWins(String conflictType) {
        assertThat(raceResponses.stream()
                        .map(response -> response.getStatusCode().value())
                        .sorted()
                        .toList())
                .containsExactly(201, 409);
        ResponseEntity<String> loser = raceResponses.stream()
                .filter(response -> response.getStatusCode().value() == 409)
                .findFirst()
                .orElseThrow();
        assertThat(loser.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(text(loser, "$.type")).isEqualTo(conflictType);
        loserUid = raceUids.get(raceResponses.indexOf(loser));
    }

    @Then("retrying the losing deposit is accepted with 201")
    public void retryingTheLoser() {
        lastMovementUid = loserUid;
        lastResponse = put(depositPath(currentAccount, loserUid), raceBody);
        assertThat(lastResponse.getStatusCode().value()).isEqualTo(201);
    }

    @Then("the request is rejected with {int} {string}")
    public void theRequestIsRejectedWith(int status, String type) {
        assertThat(lastResponse.getStatusCode().value()).isEqualTo(status);
        assertThat(lastResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(text(lastResponse, "$.type")).isEqualTo(type);
    }

    @Then("nothing is appended to the stream of {string}")
    public void nothingIsAppendedToTheStream(String name) {
        ResponseEntity<String> strong = get(strongBalancePath(name));
        assertThat(number(strong, "$.streamVersion")).isEqualTo(versionBefore);
        assertThat(number(strong, "$.amount.minorUnits")).isEqualTo(amountBefore);
    }

    @Then("the stream version of {string} is {int}")
    public void theStreamVersionIs(String name, int version) {
        assertThat(number(get(strongBalancePath(name)), "$.streamVersion")).isEqualTo(version);
    }

    @Then("a {string} notification carrying the movement UID is produced")
    public void aNotificationIsProduced(String kind) {
        assertThat(notifications.forMovement(lastMovementUid)).singleElement().satisfies(record -> {
            assertThat(record.kind()).isEqualTo(kind);
            assertThat(record.accountId().value()).isEqualTo(uid(currentAccount));
        });
    }

    @Then("no notification is produced for that movement")
    public void noNotificationIsProduced() {
        assertThat(notifications.forMovement(lastMovementUid)).isEmpty();
    }

    @Then("the retry is 200 with a body identical to the original")
    public void theRetryIsAReplay() {
        assertThat(originalResponse.getStatusCode().value()).isEqualTo(201);
        assertThat(lastResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(lastResponse.getBody()).isEqualTo(originalResponse.getBody());
    }

    @Then("the original movement stands untouched at {money}")
    public void theOriginalMovementStandsUntouched(long minorUnits) {
        assertThat(number(get(balancePath(currentAccount)), "$.amount.minorUnits"))
                .isEqualTo(minorUnits);
        List<Map<String, Object>> recorded = history(currentAccount).stream()
                .filter(entry -> lastMovementUid.toString().equals(entry.get("transactionUid")))
                .toList();
        assertThat(recorded).hasSize(1);
        assertThat(((Number) ((Map<?, ?>) recorded.getFirst().get("amount")).get("minorUnits")).longValue())
                .isEqualTo(minorUnits);
    }

    @Then("the history of {string} contains {int} transactions")
    public void theHistoryContains(String name, int count) {
        assertThat(history(name)).hasSize(count);
    }

    @Then("the history of {string} reads newest first")
    public void theHistoryReadsNewestFirst(String name) {
        List<Map<String, Object>> feed = history(name);
        assertThat(feed.stream().map(entry -> entry.get("transactionUid")).toList())
                .containsExactlyElementsOf(
                        settled(name).reversed().stream().map(UUID::toString).toList());
        assertThat(feed.stream()
                        .map(entry -> OffsetDateTime.parse((String) entry.get("transactionTime")))
                        .toList())
                .isSortedAccordingTo(Comparator.<OffsetDateTime>naturalOrder().reversed());
    }

    @Then("each history entry carries the balanceAfter its movement produced")
    public void eachHistoryEntryCarriesItsBalanceAfter() {
        long running = 0;
        for (Map<String, Object> entry : history(currentAccount).reversed()) {
            long amount = ((Number) ((Map<?, ?>) entry.get("amount")).get("minorUnits")).longValue();
            running += "IN".equals(entry.get("direction")) ? amount : -amount;
            assertThat(((Number) ((Map<?, ?>) entry.get("balanceAfter")).get("minorUnits")).longValue())
                    .isEqualTo(running);
        }
    }

    @Then("the history of {string} reconciles to its balance")
    public void theHistoryReconcilesToTheBalance(String name) {
        Map<?, ?> newest = (Map<?, ?>) history(name).getFirst().get("balanceAfter");
        assertThat(((Number) newest.get("minorUnits")).longValue())
                .isEqualTo(number(get(balancePath(name)), "$.amount.minorUnits"));
    }

    @Then("the request is refused with {string}")
    public void theRequestIsRefusedWith(String reason) {
        assertThat(lastResponse.getStatusCode().value()).isEqualTo(422);
        assertThat(lastResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(text(lastResponse, "$.type")).isEqualTo("/errors/" + reason);
    }

    @Then("the balance of {string} is still {money}")
    public void theBalanceIsStill(String name, long minorUnits) {
        theBalanceIs(name, minorUnits);
    }

    @Then("the projected balance of {string} is still {money}")
    public void theProjectedBalanceIsStill(String name, long minorUnits) {
        theBalanceIs(name, minorUnits);
    }

    /** §9.3 E1: staleness is visible to the client, not silent — the read carries how far behind it is. */
    @Then("the projected balance of {string} carries an asOf and a streamVersion behind the aggregate's")
    public void theProjectedBalanceIsVisiblyBehind(String name) {
        ResponseEntity<String> projected = get(balancePath(name));
        assertThat(OffsetDateTime.parse(text(projected, "$.asOf"))).isNotNull();
        assertThat(number(projected, "$.streamVersion"))
                .isLessThan(number(get(strongBalancePath(name)), "$.streamVersion"));
    }

    @Then("the projected balance of {string} converges to {money} within {int} seconds")
    public void theProjectedBalanceConverges(String name, long minorUnits, int seconds) {
        await().atMost(Duration.ofSeconds(seconds))
                .untilAsserted(() -> assertThat(number(get(balancePath(name)), "$.amount.minorUnits"))
                        .isEqualTo(minorUnits));
    }

    @Then("the strong balance of {string} is {money}")
    public void theStrongBalanceIs(String name, long minorUnits) {
        ResponseEntity<String> strong = get(strongBalancePath(name));
        assertThat(strong.getStatusCode().value()).isEqualTo(200);
        assertThat(number(strong, "$.amount.minorUnits")).isEqualTo(minorUnits);
    }

    /**
     * §9.3 E8: the strongest guarantee event sourcing offers. A fresh projection is folded from the raw
     * stream and compared with the live one — balance, feed and account metadata, value for value.
     */
    @Then("replaying the stream of {string} into an empty projection reproduces the projection exactly")
    public void replayingTheStreamReproducesTheProjection(String name) {
        AccountId account = new AccountId(uid(name));
        BalanceProjectionPort rebuilt = new InMemoryBalanceProjection();
        eventStore.read(account).forEach(rebuilt::apply);

        HistoryQuery everything = new HistoryQuery(null, 200, null, null);
        assertThat(rebuilt.balance(account)).isEqualTo(liveProjection.balance(account));
        assertThat(rebuilt.history(account, everything)).isEqualTo(liveProjection.history(account, everything));
        assertThat(rebuilt.account(account)).isEqualTo(liveProjection.account(account));
    }

    @Then("the balance of {string} is {money}")
    public void theBalanceIs(String name, long minorUnits) {
        ResponseEntity<String> balance = get(balancePath(name));
        assertThat(balance.getStatusCode().value()).isEqualTo(200);
        assertThat(number(balance, "$.amount.minorUnits")).isEqualTo(minorUnits);
    }

    /**
     * The refusal itself is a 422; that a {@code MovementRejected} was <em>recorded</em> is four HTTP-visible
     * consequences: the stream grew by one, no money moved, the movement never reached the settled feed, and
     * the UID replays deterministically as the same refusal (§6.3).
     */
    @Then("a {string} event is recorded")
    public void anEventIsRecorded(String eventType) {
        if (!"MovementRejected".equals(eventType)) {
            throw new IllegalArgumentException("unsupported event type: " + eventType);
        }
        ResponseEntity<String> strong = get(strongBalancePath(currentAccount));
        assertThat(number(strong, "$.streamVersion")).isEqualTo(versionBefore + 1);
        assertThat(number(strong, "$.amount.minorUnits")).isEqualTo(amountBefore);
        assertThat(transactionUids(currentAccount)).doesNotContain(lastMovementUid.toString());

        String refusal = text(lastResponse, "$.type");
        ResponseEntity<String> replay = put(lastMovementPath, lastMovementBody);
        assertThat(replay.getStatusCode().value()).isEqualTo(422);
        assertThat(text(replay, "$.type")).isEqualTo(refusal);
        // the replay answered from the stored event, so it appended nothing of its own
        assertThat(number(get(strongBalancePath(currentAccount)), "$.streamVersion"))
                .isEqualTo(versionBefore + 1);
    }

    // ------------------------------------------------------------- the wire

    private ResponseEntity<String> open(String name, String currency) {
        lastResponse = post("/api/v1/accounts", """
                {"name":"%s","currency":"%s"}""".formatted(name, currency));
        if (lastResponse.getStatusCode().value() == 201) {
            accounts.put(name, UUID.fromString(text(lastResponse, "$.accountUid")));
            currentAccount = name;
        }
        return lastResponse;
    }

    private ResponseEntity<String> deposit(String name, String currency, long minorUnits, UUID movementUid) {
        return movement("deposits", name, currency, minorUnits, movementUid);
    }

    private ResponseEntity<String> withdraw(String name, String currency, long minorUnits, UUID movementUid) {
        return movement("withdrawals", name, currency, minorUnits, movementUid);
    }

    private ResponseEntity<String> movement(
            String kind, String name, String currency, long minorUnits, UUID movementUid) {
        lastMovementPath = "/api/v1/accounts/%s/%s/%s".formatted(uid(name), kind, movementUid);
        lastMovementBody = amountBody(currency, minorUnits);
        lastMovementUid = movementUid;
        currentAccount = name;
        lastResponse = put(lastMovementPath, lastMovementBody);
        if (lastResponse.getStatusCode().value() == 201) {
            settledUids.computeIfAbsent(name, key -> new ArrayList<>()).add(movementUid);
        }
        return lastResponse;
    }

    private void captureBefore(String name) {
        ResponseEntity<String> strong = get(strongBalancePath(name));
        versionBefore = number(strong, "$.streamVersion");
        amountBefore = number(strong, "$.amount.minorUnits");
    }

    private List<String> transactionUids(String name) {
        return history(name).stream()
                .map(entry -> (String) entry.get("transactionUid"))
                .toList();
    }

    private List<Map<String, Object>> history(String name) {
        ResponseEntity<String> page = get("/api/v1/accounts/" + uid(name) + "/transactions");
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        return JsonPath.read(page.getBody(), "$.transactions");
    }

    /** The movements this scenario issued that were accepted, oldest first — the order history must reverse. */
    private List<UUID> settled(String name) {
        return settledUids.getOrDefault(name, List.of());
    }

    private String depositPath(String name, UUID movementUid) {
        return "/api/v1/accounts/%s/deposits/%s".formatted(uid(name), movementUid);
    }

    private static String amountBody(String currency, long minorUnits) {
        return """
                {"amount":{"currency":"%s","minorUnits":%d}}""".formatted(currency, minorUnits);
    }

    private static <T> T join(Future<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception failed) {
            throw new IllegalStateException("racing request never completed", failed);
        }
    }

    private String balancePath(String name) {
        return "/api/v1/accounts/" + uid(name) + "/balance";
    }

    private String strongBalancePath(String name) {
        return balancePath(name) + "?consistency=strong";
    }

    private UUID uid(String name) {
        UUID id = accounts.get(name);
        if (id == null) throw new IllegalStateException("no account named " + name + " in this scenario");
        return id;
    }

    private String currencyOf(String name) {
        return text(get(balancePath(name)), "$.amount.currency");
    }

    private ResponseEntity<String> get(String path) {
        return http().get().uri(path).retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> post(String path, String body) {
        return http().post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    private ResponseEntity<String> put(String path, String body) {
        return http().put()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);
    }

    private static String text(ResponseEntity<String> response, String path) {
        Object value = JsonPath.read(response.getBody(), path);
        return value == null ? null : value.toString();
    }

    private static long number(ResponseEntity<String> response, String path) {
        return ((Number) JsonPath.read(response.getBody(), path)).longValue();
    }
}
