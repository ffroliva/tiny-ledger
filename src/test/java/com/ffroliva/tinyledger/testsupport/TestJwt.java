package com.ffroliva.tinyledger.testsupport;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;

/**
 * Mints a token no issuer in the system will accept — signed by a key generated fresh in this JVM,
 * which Keycloak's JWKS cannot have published because the key did not exist until this class loaded.
 * Its only job is minting the token that {@code SecurityConfigIT#aTokenThisIssuerDidNotMintIsRefused}
 * proves is refused, which shows the resource server actually validates against the container rather
 * than merely still accepting what it always accepted.
 *
 * <p>Generated per-JVM rather than loaded from a committed key file: a key nobody else ever holds is
 * unknowable to Keycloak by construction, so there is nothing here for a secret scanner to flag and no
 * committed keypair that could ever leak.
 */
public final class TestJwt {

    /** Blank, and now incidental: the token is refused on signature, not on issuer. */
    public static final String ISSUER = "";

    private static final RSAPrivateKey KEY = generate();

    private TestJwt() {}

    private static RSAPrivateKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return (RSAPrivateKey) generator.generateKeyPair().getPrivate();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("could not generate the test signing key", e);
        }
    }

    public static String token(String subject) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(ISSUER)
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
