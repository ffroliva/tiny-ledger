package com.ffroliva.tinyledger.testsupport;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

/**
 * Mints locally-signed tokens so the suite needs no Keycloak. Real Keycloak is a compose service proven by
 * a boot proof, not by ITs — keeping the suite hermetic and fast.
 *
 * <p>The keypair is a <em>committed file</em> rather than one generated at class-init, and that is the whole
 * point: a generated key could only reach the context as a {@code JwtDecoder} bean, and the only ways to add
 * a bean to one IT class are {@code @Import} or a per-class {@code @TestConfiguration} — both of which change
 * the context cache key and fork the {@code full} context (ADR 0003). A file can instead be named by a
 * property on {@link AbstractIntegrationTest}'s shared {@code @DynamicPropertySource}, so every IT keeps an
 * identical cache key and the suite keeps one context.
 *
 * <p>Only the private half is read here: an {@code RSAPrivateKey} is all {@code RSASSASigner} needs, and the
 * resource server reads the public half itself from {@code classpath:test-jwt-public.pem}.
 */
public final class TestJwt {

    /**
     * The issuer both halves of the test setup agree on, and it is blank on purpose. Boot picks the
     * network-free {@code public-key-location} decoder only when {@code issuer-uri} has no text, but it adds
     * a {@code JwtIssuerValidator} whenever that property is merely non-{@code null} — so blanking the
     * property is not enough on its own, and the minted token has to claim the same blank issuer. Measured,
     * not assumed: see the task 3 report.
     */
    public static final String ISSUER = "";

    private static final RSAPrivateKey KEY = load();

    private TestJwt() {}

    private static RSAPrivateKey load() {
        try (InputStream in = TestJwt.class.getResourceAsStream("/test-jwt-private.pem")) {
            if (in == null) throw new IllegalStateException("test-jwt-private.pem is not on the test classpath");
            String pem = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "");
            byte[] der = Base64.getMimeDecoder().decode(pem);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("could not read the committed test signing key", e);
        }
    }

    public static String token(String subject, String... roles) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(ISSUER)
                    .claim("roles", List.of(roles))
                    .issueTime(new Date())
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("could not mint the test token", e);
        }
    }
}
