package com.ffroliva.tinyledger.platform;

import com.ffroliva.tinyledger.shared.StandalonePrincipal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
            return jwt.getToken().getSubject();
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

    /** Instance, not static — a static sibling would force the {@code standalone} guard's deletion. */
    public Set<String> roles() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwt)) {
            return Set.of();
        }
        List<String> roles = jwt.getToken().getClaimAsStringList("roles");
        return roles == null ? Set.of() : new LinkedHashSet<>(roles);
    }
}
