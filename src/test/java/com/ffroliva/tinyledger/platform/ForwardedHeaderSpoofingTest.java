package com.ffroliva.tinyledger.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * <strong>This is the security gate for the TLS work, and it is the half that can regress
 * silently.</strong> Spec §6.1 row 4 rate-limits <em>any traffic, per IP</em> at 300/minute, and
 * {@link IpBackstopFilter} reads {@code getRemoteAddr()}. Putting Traefik in front changes every
 * request's source address to the proxy's, so the application has to be told to read the forwarded
 * address instead — and the moment it is told that, the question becomes <em>from whom</em>.
 *
 * <p>If the answer is "from anyone", the backstop is gone. A caller sends a different
 * {@code X-Forwarded-For} on every request, lands in a different bucket every time, and never
 * exhausts one. Adding TLS would then have <em>removed</em> a control while looking like it added
 * one, which is the specific outcome {@code docs/security-material.md} names as the trap that
 * matters most in this piece of work.
 *
 * <p>So this test asserts the negative: with the shipped configuration, a client that is not the
 * trusted proxy cannot change which bucket it is metered in, no matter what it puts in the header.
 * The bucket key <em>is</em> the observable — "which address did the application think you are" has
 * no other externally visible answer, and inventing an endpoint to report it would be inventing a
 * disclosure.
 *
 * <p><strong>It is deliberately paired with {@link ForwardedHeaderTrustedProxyTest}, and neither is
 * worth much alone.</strong> This one would pass just as happily if the valve were missing, the
 * property misspelled, or the strategy left at {@code none} — a green run that checked nothing
 * ({@code AGENTS.md} trap 1). Its partner runs the identical two requests with
 * {@code internal-proxies} moved to cover the caller and requires the opposite outcome. Together
 * they are differential: one property, two contradictory results, so neither result can be an
 * accident of the mechanism being absent.
 *
 * <p><strong>Context fork, with its reason</strong> ({@code AGENTS.md} trap 5):
 * {@code server.forward-headers-strategy} is a web-server-factory setting applied when the server is
 * built, so it cannot be varied inside one context, and {@code MockMvc} has no socket and therefore
 * no {@code remoteAddr} to rewrite. A real port is the only place this is observable. Runs under
 * {@code standalone}, so it starts no containers and stays on the fast {@code verify} path
 * (ADR 0003).
 *
 * <p>Two properties are overridden and both are load-bearing. {@code capacity=1} makes the second
 * request of a pair the one that trips, so the assertion needs two calls rather than 301.
 * {@code exempt-ips} is emptied because {@code application-standalone.properties} exempts
 * {@code 127.0.0.1} — §6.1 records that rate limiting is inert in this mode by design, and without
 * this line every request here would skip both buckets and the test would pass having measured
 * nothing.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ledger.rate-limit.ip-backstop.capacity=1", "ledger.rate-limit.exempt-ips="})
@ActiveProfiles("standalone")
class ForwardedHeaderSpoofingTest {

    @LocalServerPort
    int port;

    /**
     * The two addresses are from {@code TEST-NET-3} (RFC 5737), which exists for exactly this: they
     * are documentation addresses, guaranteed never to be routed, so nothing here can accidentally
     * describe a real host.
     */
    @Test
    void aSpoofedForwardedForCannotBuyASecondBucket() {
        assertThat(getWithForwardedFor("203.0.113.1"))
                .as("first request from this source address consumes its only backstop token")
                .isEqualTo(200);

        assertThat(getWithForwardedFor("203.0.113.2"))
                .as("a DIFFERENT X-Forwarded-For from an untrusted peer must NOT open a second bucket — "
                        + "if this is 200, any caller can walk past §6.1's per-IP backstop by varying one header")
                .isEqualTo(429);
    }

    static int getWithForwardedFor(int port, String forwardedFor) {
        return org.springframework.web.client.RestClient.create()
                .get()
                .uri("http://127.0.0.1:" + port + "/api/v1/accounts")
                .header("X-Forwarded-For", forwardedFor)
                .exchange((request, response) -> response.getStatusCode().value(), false);
    }

    private int getWithForwardedFor(String forwardedFor) {
        return getWithForwardedFor(port, forwardedFor);
    }
}
