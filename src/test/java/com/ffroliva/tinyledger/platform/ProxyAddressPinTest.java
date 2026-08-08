package com.ffroliva.tinyledger.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * <strong>This test exists because the thing it checks was deleted once and nothing noticed.</strong>
 *
 * <p>{@link ForwardedHeaderSpoofingTest} and {@link ForwardedHeaderTrustedProxyTest} prove the
 * <em>mechanism</em>: given an {@code internal-proxies} value that matches the caller, the forwarded
 * address is honoured; given one that does not, it is ignored. Neither can prove the <em>deployed
 * value matches the address Traefik actually holds</em>, because both supply their own value as a
 * test property and neither has a Compose network in front of it.
 *
 * <p>So when a rewrite of {@code docker-compose.yml}'s {@code command:} list removed the
 * {@code networks: default: ipv4_address:} block that sat between it and {@code volumes:}, every
 * test stayed green. Docker then allocated Traefik an address from the bottom of the subnet,
 * {@code internal-proxies} matched nothing, {@code RemoteIpValve} silently did nothing, and §6.1's
 * per-IP backstop degraded into a single global bucket that any one client could exhaust for
 * everyone — the outcome {@link ForwardedHeaderTrustedProxyTest}'s javadoc calls "a denial of
 * service dressed as a rate limit".
 *
 * <p><strong>The by-hand check that was run did not catch it either, and that is the lesson.</strong>
 * Four requests with four different spoofed {@code X-Forwarded-For} values score
 * {@code 401, 401, 429, 429} <em>whether the trust works or not</em>: with it, Traefik appends the
 * real client and the valve discards the spoof; without it, the valve never runs. Identical output,
 * opposite configurations — a control that is not differential is not a control (AGENTS.md trap 7).
 *
 * <p>This is a plain unit test that reads the two files as text. It starts nothing, so it stays on
 * the fast {@code verify} path (ADR 0003), and it is deliberately textual rather than a YAML parse:
 * the thing being asserted is that <em>two literals in two files agree</em>, and a parser that
 * resolved defaults for us would hide exactly the divergence being hunted.
 */
class ProxyAddressPinTest {

    private static final Path COMPOSE = Path.of("docker", "docker-compose.yml");
    private static final Path PROPERTIES = Path.of("src", "main", "resources", "application.properties");

    /**
     * Both sites spell the address through the same variable with the same fallback, e.g.
     * {@code ${TINY_LEDGER_TRAEFIK_IP:-10.89.0.250}}. Capturing the whole expression rather than
     * just the literal is deliberate: if one site loses the variable and keeps the literal, the two
     * still agree on the value but no longer move together, and that is worth failing on.
     */
    private static String captureOnce(Path file, String regex) throws IOException {
        Matcher m = Pattern.compile(regex).matcher(Files.readString(file));
        assertThat(m.find())
                .as(
                        "%s must contain a match for /%s/ — if this fails the line was deleted or renamed, "
                                + "and §6.1's per-IP backstop is silently metering the whole world as one caller",
                        file, regex)
                .isTrue();
        String first = m.group(1);
        assertThat(m.find())
                .as(
                        "%s declares /%s/ more than once; two sites can disagree, so there must be exactly one",
                        file, regex)
                .isFalse();
        return first;
    }

    @Test
    void traefikIsPinnedToTheAddressTheApplicationTrusts() throws IOException {
        String pinned = captureOnce(COMPOSE, "(?m)^\\s*ipv4_address:\\s*(\\S+)\\s*$");
        String trusted = captureOnce(COMPOSE, "(?m)^\\s*LEDGER_TRUSTED_PROXIES:\\s*(\\S+)\\s*$");

        assertThat(trusted)
                .as(
                        "the application is told to trust %s but Traefik is pinned to %s — the forwarded-header "
                                + "trust boundary matches an address nothing holds, so RemoteIpValve never fires",
                        trusted, pinned)
                .isEqualTo(pinned);
    }

    /**
     * The property's own fallback is the value a jar inherits when nothing sets the variable, so it
     * has to name the same host as Compose does. A drift here is invisible in Compose (which passes
     * the variable explicitly) and bites only the deployment that forgot to.
     */
    @Test
    void thePropertyFallbackNamesTheSameAddressAsCompose() throws IOException {
        String composeDefault = defaultOf(captureOnce(COMPOSE, "(?m)^\\s*ipv4_address:\\s*(\\S+)\\s*$"));
        String propertyDefault =
                defaultOf(captureOnce(PROPERTIES, "(?m)^server\\.tomcat\\.remoteip\\.internal-proxies=(\\S+)\\s*$"));

        assertThat(propertyDefault)
                .as("application.properties falls back to %s while Compose pins %s", propertyDefault, composeDefault)
                .isEqualTo(composeDefault);
    }

    /**
     * {@code ${NAME:-value}} or {@code ${NAME:value}} → {@code value}; anything else is already a
     * literal.
     *
     * <p><strong>The two files spell the default differently and both spellings are correct.</strong>
     * Compose uses shell syntax, {@code ${NAME:-default}}; Spring's placeholder resolver uses a
     * single colon, {@code ${NAME:default}}. They are not interchangeable — a {@code :-} in a
     * properties file makes the default the literal string {@code -10.89.0.250}, which is not an
     * address and which Tomcat would compile into a pattern that matches nothing. Caught by this
     * test on its first run, which is the only reason it is written down here.
     */
    private static String defaultOf(String expression) {
        Matcher m = Pattern.compile("^\\$\\{[^:}]+:-?([^}]+)}$").matcher(expression);
        return m.matches() ? m.group(1) : expression;
    }
}
