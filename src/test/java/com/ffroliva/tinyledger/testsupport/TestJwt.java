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
 * Mints a token no issuer in the system will accept — signed by a key Keycloak's JWKS will never publish.
 * Its only remaining job is minting the token that {@code SecurityConfigIT#aTokenThisIssuerDidNotMintIsRefused}
 * proves is refused, which shows the resource server actually validates against the container rather than
 * merely still accepting what it always accepted.
 */
public final class TestJwt {

    /** Blank, and now incidental: the token is refused on signature, not on issuer. */
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
