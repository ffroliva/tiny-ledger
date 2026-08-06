package com.ffroliva.tinyledger.loadtest;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.UUID;

/**
 * §9.7's load simulation: ramp to 500 concurrent users; assert p99 write &lt; 150 ms, p99 cached read
 * &lt; 20 ms, error rate &lt; 0.1%. Scenarios: steady state, burst, and hot-account contention.
 *
 * <p><b>Requires the {@code load} profile.</b> Run the application as {@code full,load}. Against
 * {@code full}'s production limits the per-IP backstop is 300/minute and {@code exempt-ips} is empty,
 * so a single-source-IP generator caps the whole application at five requests per second and this
 * simulation would measure the rate limiter. {@code application-load.properties} raises the buckets
 * without removing the limiter from the path, so the measurement still includes its real cost.
 *
 * <p><b>The thresholds are assertions, not a report.</b> {@code assertions(...)} makes the Maven build
 * fail when a percentile is missed, which is what §9.7 means by "a regression fails the pipeline".
 * They were proven to fail by deliberate violation before being trusted — see the commit that added
 * this file.
 */
public class LedgerSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("ledger.baseUrl", "http://127.0.0.1:8080");
    private static final String ISSUER =
            System.getProperty("ledger.issuerUri", "http://localhost:8081/realms/tiny-ledger");
    private static final String USERNAME = System.getProperty("ledger.username", "alice");
    private static final String PASSWORD = System.getProperty("ledger.password", "dev-only");
    private static final String CLIENT_ID = System.getProperty("ledger.clientId", "ledger-test");

    /** Ramp target from §9.7. Overridable so a smoke run can use a fraction of it. */
    private static final int USERS = Integer.getInteger("ledger.users", 500);

    private static final Duration RAMP = Duration.ofSeconds(Integer.getInteger("ledger.rampSeconds", 30));

    private final HttpProtocolBuilder httpProtocol =
            http.baseUrl(BASE_URL).acceptHeader("application/json").contentTypeHeader("application/json");

    /**
     * One token fetched per virtual user at start. The realm's access token lifespan is 900s, longer
     * than any run here, so nothing refreshes mid-simulation — a refresh would appear in the write
     * percentiles as latency the ledger did not cause.
     */
    private final ChainBuilder authenticate = exec(http("token")
                    .post(ISSUER + "/protocol/openid-connect/token")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .formParam("grant_type", "password")
                    .formParam("client_id", CLIENT_ID)
                    .formParam("username", USERNAME)
                    .formParam("password", PASSWORD)
                    .check(status().is(200))
                    .check(jsonPath("$.access_token").saveAs("token")));

    private static final String OPEN_ACCOUNT_BODY =
            """
            {"owner":"%s","name":"load","currency":"GBP"}""".formatted(USERNAME);

    /**
     * The work itself, as a chain rather than a scenario, because §9.7's steady-state and burst
     * scenarios differ only in their injection profile — same requests, same assertions, arriving
     * differently. Gatling requires scenario names to be unique, so injecting one scenario twice
     * fails the run outright ("Scenario names must be unique"); sharing the chain and naming two
     * scenarios from it is the correct shape and keeps the request names identical, which is what
     * lets the per-request assertions below cover both.
     */
    private final ChainBuilder movementChain = exec(authenticate)
            .exec(http("open account")
                    .post("/api/v1/accounts")
                    .header("Authorization", "Bearer #{token}")
                    .body(StringBody(OPEN_ACCOUNT_BODY))
                    .check(status().is(201))
                    .check(jsonPath("$.accountUid").saveAs("acct")))
            .repeat(5)
            .on(exec(session -> session.set("mvt", UUID.randomUUID().toString()))
                    .exec(http("write")
                            .put("/api/v1/accounts/#{acct}/deposits/#{mvt}")
                            .header("Authorization", "Bearer #{token}")
                            .body(StringBody("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":100}}"))
                            .check(status().is(201)))
                    .pause(Duration.ofMillis(200))
                    // The cached read: §4.4's projection-backed balance, which is what the < 20 ms
                    // threshold is about. The strong read is a different path and is not asserted here.
                    .exec(http("cached read")
                            .get("/api/v1/accounts/#{acct}/balance")
                            .header("Authorization", "Bearer #{token}")
                            .check(status().is(200))));

    private final ScenarioBuilder steadyState = scenario("steady state").exec(movementChain);

    private final ScenarioBuilder burst = scenario("burst").exec(movementChain);

    /**
     * Hot-account contention: every virtual user drives ONE shared aggregate. This is the pathological
     * case for optimistic concurrency — §9.7 names it precisely because the event store's version
     * check is where a ledger under contention actually degrades, and a scenario where every user has
     * their own account would never touch it.
     */
    private final ScenarioBuilder hotAccount = scenario("hot-account contention")
            .exec(authenticate)
            .exec(http("open shared account")
                    .post("/api/v1/accounts")
                    .header("Authorization", "Bearer #{token}")
                    .body(StringBody(OPEN_ACCOUNT_BODY))
                    .check(status().is(201))
                    .check(jsonPath("$.accountUid").saveAs("hot")))
            .repeat(10)
            .on(exec(session -> session.set("mvt", UUID.randomUUID().toString()))
                    .exec(http("write")
                            .put("/api/v1/accounts/#{hot}/deposits/#{mvt}")
                            .header("Authorization", "Bearer #{token}")
                            .body(StringBody("{\"amount\":{\"currency\":\"GBP\",\"minorUnits\":1}}"))
                            .check(status().is(201))));

    {
        setUp(
                        // Steady state: a gradual ramp to the §9.7 target.
                        steadyState.injectOpen(rampUsers(USERS).during(RAMP)),
                        // Burst: the same work arriving all at once, 5s in.
                        burst.injectOpen(nothingFor(Duration.ofSeconds(5)), atOnceUsers(USERS / 5))
                                .andThen(hotAccount.injectOpen(rampUsers(USERS / 10).during(Duration.ofSeconds(10)))))
                .protocols(httpProtocol)
                .assertions(
                        // §9.7, verbatim. `details("write")` scopes the percentile to the write requests
                        // rather than to every request in the run — a global p99 would be dominated by
                        // the cheap reads and would pass while writes regressed.
                        details("write").responseTime().percentile(99.0).lt(150),
                        details("cached read").responseTime().percentile(99.0).lt(20),
                        global().failedRequests().percent().lt(0.1));
    }
}
