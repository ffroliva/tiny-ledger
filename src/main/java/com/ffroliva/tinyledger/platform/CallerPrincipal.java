package com.ffroliva.tinyledger.platform;

import com.ffroliva.tinyledger.shared.StandalonePrincipal;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * §6.4: the caller principal, read once at the web edge and passed down as a plain {@code String} so
 * no use case ever sees a framework type. In {@code standalone} there is no authentication, and the
 * fixed principal is the documented contract rather than a fallback.
 */
@Component
public class CallerPrincipal {

    private final boolean standalone;

    public CallerPrincipal(Environment environment) {
        this.standalone = environment.matchesProfiles("standalone") || environment.getActiveProfiles().length == 0;
    }

    public String current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            // `sub` is optional as far as the resource server is concerned: JwtValidators checks the
            // issuer and the timestamps, never the subject. A subject-less token is authenticated but
            // anonymous, so it takes the same path as no authentication at all — otherwise `null` is
            // stamped on the account as its owner and every subject-less token shares one principal.
            String subject = jwt.getToken().getSubject();
            if (subject != null && !subject.isBlank()) {
                return subject;
            }
        }
        // Fail CLOSED outside standalone. Returning the fixed principal whenever authentication is
        // absent would turn a security misconfiguration into a *wrong answer* — the ownership check
        // would compare against whatever "local" happens to own — instead of a refusal. FailClosedGuard
        // asserts the same principle for profile configuration; this is its per-request counterpart.
        if (!standalone) {
            throw new IllegalStateException("no authenticated principal outside the standalone profile");
        }
        return StandalonePrincipal.NAME;
    }

    /**
     * §6.4: whether the caller holds {@code ledger:admin} — the one fact
     * {@code RecordMovementService} needs to widen its ownership check. {@code standalone} has no
     * {@link Authentication} at all (permitAll, no bearer token), so this is always {@code false}
     * there — consistent with §15.8: on-behalf-of has no meaning where there is only one principal.
     */
    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt
                && jwt.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ledger:admin"));
    }
}
