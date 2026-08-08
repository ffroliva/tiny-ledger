package com.ffroliva.tinyledger.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * The other half of {@link ForwardedHeaderSpoofingTest}, and the half that makes it mean something.
 *
 * <p>That test proves a spoofed {@code X-Forwarded-For} buys nothing. On its own that proof is
 * consistent with the forwarded-header mechanism being <em>entirely absent</em> — no valve, a
 * misspelled property, {@code server.forward-headers-strategy} never set. It would go green in all
 * of those, and behind Traefik the application would then meter the whole world as one address:
 * §6.1's per-IP backstop would fire for everyone the moment any one caller was noisy, which is a
 * denial of service dressed as a rate limit.
 *
 * <p>So this class runs the identical two requests with one property moved — {@code internal-proxies}
 * widened to cover the test client — and requires the opposite outcome. Same request shape, same
 * capacity of 1, contradictory results either side of a single value. That is the differential form
 * {@code AGENTS.md} asks for: it proves both that the pattern matches and that the other test's
 * refusal is a real refusal rather than an absence.
 *
 * <p><strong>The override here is a TEST fixture and never a deployment shape.</strong>
 * {@code internal-proxies=127.0.0.1} means "a proxy on loopback is trusted", which for a jar running
 * on a shared host would let any local process forge a client address. It is correct here only
 * because the only thing on this loopback is this test. The shipped value in
 * {@code application.properties} names Traefik's pinned Compose address and nothing else.
 *
 * <p>Context fork, capacity and {@code exempt-ips}: same reasons, written out in
 * {@link ForwardedHeaderSpoofingTest}. No containers; fast {@code verify} path (ADR 0003).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.tomcat.remoteip.internal-proxies=127.0.0.1",
            "ledger.rate-limit.ip-backstop.capacity=1",
            "ledger.rate-limit.exempt-ips="
        })
@ActiveProfiles("standalone")
class ForwardedHeaderTrustedProxyTest {

    @LocalServerPort
    int port;

    @Test
    void aTrustedProxysForwardedForSelectsTheBucket() {
        assertThat(ForwardedHeaderSpoofingTest.getWithForwardedFor(port, "203.0.113.1"))
                .as("first client, first bucket")
                .isEqualTo(200);

        assertThat(ForwardedHeaderSpoofingTest.getWithForwardedFor(port, "203.0.113.2"))
                .as("a DIFFERENT client behind the SAME trusted proxy must get its own bucket — a 429 here "
                        + "means the forwarded address is being ignored and every caller shares one limit")
                .isEqualTo(200);
    }
}
