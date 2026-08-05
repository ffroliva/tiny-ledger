# Plan 3 research — Keycloak, RBAC, FAPI 2, DPoP

**Date:** 2026-08-05 · **Author:** overnight queue item B1 (Stream B, documents only)
**Status:** research. Nothing here is decided, approved, or scheduled.

## What this is / what it is not

**It is** a survey of how the relevant standards actually read, what Keycloak 26.7 and Spring
Security 7.1 actually do, and what the seams in *this* codebase actually look like today — assembled
so a human can make Plan 3's decisions with citations in front of them instead of recollections.

**It is not** a plan, a spec revision, an ADR, or a recommendation. Where I have a view I mark it
**Leaning** and give the reasoning; a leaning is an input to a decision, not the decision. Every
decision I found is collected in §8 as a question with options and a trade-off.

Three standing constraints on this document. Where a claim is about a *specification*, it cites the
RFC/spec section. Where it is about an *implementation*, it cites the version and the source file.
Where it is about *this repository*, it cites the file and line. Where sources disagree — including
where a document already in this repo is now out of date — I say so rather than picking silently.

Two claims below are marked **[inferred]**: I could not run the build tonight (Stream A owns it), so
anything that would need a green or red run to settle is flagged as such rather than asserted.

---

## 1. Ground truth, before any of the standards

Everything downstream depends on these, and two of them contradict the review this plan builds on.

| Fact | Evidence |
|---|---|
| Spring Boot **4.1.0**, Java **25** | `pom.xml:9,16` |
| Boot 4.1.0 manages **Spring Security 7.1.0**, Spring Framework 7.0.8 | `spring-boot-dependencies-4.1.0.pom` on Maven Central |
| **No** `spring-boot-starter-security` and **no** `spring-boot-starter-oauth2-resource-server` on the classpath | `pom.xml` — grep for `security`/`oauth2` returns nothing |
| **No Keycloak anywhere.** `docker/docker-compose.yml` has exactly three services: postgres, redis, kafka. There is no `docker/keycloak/` directory and no `realm-tiny-ledger.json` | `docker/docker-compose.yml` |
| Current Keycloak release line is **26.7.0** | <https://www.keycloak.org/docs/latest/release_notes/index.html> |
| The OpenAPI contract already declares a global `security: [bearerAuth]` with `type: http, scheme: bearer, bearerFormat: JWT`, and every operation already lists 401/403 | `docs/api/openapi.yaml:41-42,358-362` |
| Nothing writes `traceId` to the MDC. `ErrorHandlingAdvice.traced()` reads it and silently omits the property when absent | `platform/ErrorHandlingAdvice.java:118-123` |
| `FailClosedGuard` refuses to start **standalone** if `spring.security.oauth2.resourceserver.jwt.issuer-uri` is present. There is **no converse guard**: nothing refuses to start `full` *without* an issuer-uri | `platform/FailClosedGuard.java:14-25` |

### 1.1 Two corrections to `2026-08-04-open-banking-standards-review.md` §1/§5

The review is eight weeks fresher than the versions it cites, and both of its Plan-3-load-bearing
version claims have moved:

1. **"Spring Security DPoP resource-server support — 6.5+ — DPoP proofs auto-validated under
   `oauth2ResourceServer`"** (review §1, last row) is wrong in both halves.
   - Spring Security 6.5 shipped the *classes* (`DPoPAuthenticationProvider` is `@since 6.5`,
     `DPoPProofJwtDecoderFactory` is `@since 6.5`) but **not** the configuration DSL. The
     `OAuth2ResourceServerConfigurer.dPoP(Customizer)` method and `DPoPAuthenticationConverter` are
     both **`@since 7.1`**.
   - It is **not automatic**. The Javadoc on `dPoP(...)` reads "Enables DPoP-bound access token
     support", and `OAuth2ResourceServerConfigurer.configure(H)` only calls
     `this.dPoPConfigurer.configure(http)` when `this.dPoPConfigurer != null` — i.e. when you asked
     for it. Boot's resource-server auto-configuration does not ask for it.

   The good news is that this repo is on Boot 4.1 → Security 7.1, so the DSL *is* available. The
   review's cost estimate ("configuration rather than code") survives; its mechanism does not.
2. **Keycloak 26.4** (review §1) has been superseded by 26.7. FAPI 2 Final and supported DPoP both
   landed in **26.4.0** and are still present, so the substance holds — but a realm JSON written
   against 26.4 should be exercised against whatever image the compose stack actually pins, and the
   pin should be explicit rather than `:latest`.

*Sources: `OAuth2ResourceServerConfigurer.java`, `DPoPAuthenticationProvider.java`,
`DPoPAuthenticationConverter.java` on `spring-projects/spring-security@main`;
<https://www.keycloak.org/docs/latest/release_notes/index.html> §Keycloak 26.4.0.*

---

## 2. Keycloak realm and client setup for FAPI 2

### 2.1 What a FAPI 2 "profile" is in Keycloak

Keycloak does not have a realm-level "FAPI mode". It has **client policies**: a *policy* is a set of
conditions plus a set of *profiles*; a *profile* is a list of *executors*, each of which validates or
auto-configures one property of a client. Ten **global profiles** ship with every realm and cannot be
edited, but can be copied as a template. **No client policy exists in a realm by default** — the
global profiles are inert until an administrator writes a policy that references one.

*Source: <https://www.keycloak.org/docs/latest/server_admin/index.html> §Client Policies →
Architecture/Configuration; <https://www.keycloak.org/securing-apps/oidc-layers> §FAPI Support.*

So "attach the `fapi-2-dpop-security-profile` client policy" (review §5) is precisely: add a
`clientPolicies` block to `realm-tiny-ledger.json` with a policy whose condition selects the
tiny-ledger clients and whose `profiles` list names that global profile.

### 2.2 What the FAPI 2 profiles actually enforce

Read from Keycloak's own global-profile definition rather than prose, so the list is exact.
`services/src/main/resources/keycloak-default-client-profiles.json` on `keycloak/keycloak@main`:

| Executor | `fapi-2-security-profile` | `fapi-2-dpop-security-profile` | What it does to a client |
|---|---|---|---|
| `confidential-client` | ✔ | ✔ | Public clients are refused |
| `secure-client-authenticator` | ✔ | ✔ | `allowed: [client-jwt, client-x509]`, `default: client-jwt` — i.e. **`private_key_jwt` or mTLS only; client secrets are refused** |
| `secure-client-uris` | ✔ | ✔ | Redirect/base URIs must be secure, non-wildcard |
| `secure-signature-algorithm` | ✔ (`PS256`) | ✔ (`PS256`) | Default signature algorithm |
| `secure-signature-algorithm-signed-jwt` | ✔ (`require-client-assertion: false`) | ✔ | Constrains the client-assertion algorithm |
| `consent-required` | ✔ (`auto-configure: true`) | ✔ | **Turns consent on for the client** |
| `full-scope-disabled` | ✔ (`auto-configure: true`) | ✔ | **Turns "Full scope allowed" off** |
| `reject-implicit-grant` | ✔ | ✔ | Implicit/hybrid response types refused |
| `pkce-enforcer` | ✔ (`auto-configure: true`) | ✔ | PKCE with `S256` |
| `secure-client-authentication-assertion` | ✔ | ✔ | Client-assertion `aud` must be the issuer identifier, as a string |
| `secure-par-content` | ✔ | ✔ | Validates the *content* of PAR requests |
| `holder-of-key-enforcer` | **✔ (`auto-configure: true`)** | — | **mTLS certificate-bound tokens (RFC 8705)** |
| `dpop-bind-enforcer` | — | **✔** (`enforce-authorization-code-binding-to-dpop: false`, `allow-only-refresh-token-binding: false`) | DPoP-bound tokens |

Four consequences that are not obvious from the review:

- **The two profiles are not interchangeable.** `fapi-2-security-profile` carries
  `holder-of-key-enforcer`, i.e. it demands mTLS certificate-bound tokens. Attaching it to a client
  in a compose stack with no client PKI will fail. If DPoP is the chosen sender-constraint,
  `fapi-2-dpop-security-profile` is not a variant, it is *the* one — and the review's own table
  ("Skip mTLS") already implies that without saying that the non-DPoP profile requires it.
- **`full-scope-disabled` is the one that will silently break RBAC.** With full scope off, realm
  roles stop being included in tokens automatically; `ledger:writer` and friends must be granted to
  the client explicitly (dedicated client scope / scope mappings) or every request arrives with an
  empty roles claim and 403s. The failure is not a startup error, it is every authorisation test
  going red at once for a reason that looks like an authorisation bug.
- **`consent-required` changes the interactive login flow.** Any browser-driven e2e step gains a
  consent screen. It is irrelevant to a client-credentials service account, which never has a user
  to consent.
- **Neither profile forces PAR.** `secure-par-content` validates PAR content; requiring PAR is a
  per-client setting (`require.pushed.authorization.requests`). There is no PAR-requiring executor in
  the whole global set (`reject-ropc-grant`, `secure-*`, `pkce-enforcer`,
  `holder-of-key-enforcer`, `dpop-bind-enforcer`, `saml-*` — that is the complete list). FAPI 2.0
  §5.3.2.2 *does* require the AS to reject non-PAR authorization requests, so if PAR is wanted it has
  to be set on the client, not inherited from the profile.

### 2.3 The DPoP switch is separate from the profile

Client-level: **"Require DPoP bound tokens"** under Capability config. If it is **off**, a client may
still send a DPoP proof at the token endpoint; Keycloak verifies it and adds the thumbprint to the
token, but does not *require* binding. So a realm can end up issuing a mix of bound and unbound
tokens without any error appearing anywhere.

*Source: <https://www.keycloak.org/2025/10/dpop-support-26-4>.*

26.4 also added: DPoP accepted on all bearer-token endpoints including the Admin and Account APIs;
binding refresh tokens only, for public clients; and `dpop_jkt` in authorization requests.

### 2.4 What the FAPI 2 spec puts on the authorization server

FAPI 2.0 Security Profile (Final, Feb 2025) §5.3.2.1, verbatim obligations relevant here:
confidential clients only; reject ROPC; **only issue sender-constrained access tokens** (mTLS or
DPoP); authenticate clients with mTLS or `private_key_jwt`; only accept its own issuer identifier as
a **string** in the client-assertion `aud`; **no refresh-token rotation** except in extraordinary
circumstances; authorization codes ≤ **60 s**; if using DPoP, **shall support** Authorization Code
Binding to DPoP Key (RFC 9449 §10.1); and accept JWTs with `iat`/`nbf` 0–10 s in the future while
rejecting > 60 s (§5.3.2.1 NOTE 3).

§5.4.1: JWTs use **`PS256`, `ES256` or `EdDSA` (Ed25519) only**, never `none`; RSA keys ≥ 2048 bits;
EC keys ≥ 224 bits.

§5.3.2.1 NOTE 2 is the one worth reading twice: only the authorization-code and CIBA flows have been
through the formal security analysis, and the profile is "structured to support a variety of grants…
for example the client credentials grant". So a client-credentials service account under FAPI 2 is
*within* the document's intent but *outside* its analysis.

*Source: <https://openid.net/specs/fapi-security-profile-2_0-final.html>.*

### 2.5 The test-user cast, against a realm that does not exist yet

Spec §6.4 already specifies six users (`alice`, `bob`, `carol`, `dave`, `mallory`, `ledger-cli`) and
the approved admin proposal adds `trent`. Two things that fall out of §2.2 above:

- With `secure-client-authenticator` in force, **`ledger-cli` cannot use a client secret**. It needs
  a key pair, a JWK registered on the client, and PS256-signed client assertions. Spec §11's
  one-line "OAuth2 client-credentials against Keycloak; token cached" becomes a signing
  implementation. That cost belongs in the Plan 4 CLI estimate, not in the realm-JSON estimate.
- §6.4's "`ACC-001`…`ACC-900` … pinned to deterministic UUIDs by
  `docker/keycloak/realm-tiny-ledger.json` plus a seed script the compose stack runs once" is
  unbuilt, and the seed script has to open accounts *through the API* (accountUid is
  server-generated, `LedgerController.openAccount` → `ids.next()`), so it needs a token, which needs
  the realm, which needs Keycloak up. That is a startup ordering problem in compose, not a
  fixture-data problem.

---

## 3. DPoP sender-constrained tokens, end to end

### 3.1 What the RFC requires of the resource server

RFC 9449 §7.1: a DPoP-bound access token arrives as `Authorization: DPoP <token>` and the proof as a
separate `DPoP:` header. The resource server **MUST** check that a proof was received, check it per
§4.3, and check that the proof's public key matches the key the token is bound to (§6). Access
**MUST NOT** be granted unless all checks succeed. Failures are signalled with
`WWW-Authenticate: DPoP error="invalid_dpop_proof"` (or `invalid_token`), optionally with
`algs="ES256 PS256"` and `use_dpop_nonce`.

§4.3's checklist, all twelve items: one and only one `DPoP` header; a single well-formed JWT; all
§4.2 claims present; `typ` is `dpop+jwt`; `alg` is a registered *asymmetric* algorithm, not `none`;
signature verifies against the header's `jwk`; the `jwk` contains no private key; `htm` matches the
request method; `htu` matches the request URI **ignoring query and fragment**; `nonce` matches if one
was issued; creation time within an acceptable window (§11.1); and when presented with an access
token, `ath` equals the hash of that token *and* the token's bound key matches the proof's key.

§4.3 also says servers **SHOULD** apply RFC 3986 §6.2.2/§6.2.3 normalization before comparing `htu`.

§11.1: servers **MUST** only accept proofs for a limited time after creation, "preferably… on the
order of seconds or minutes". Tracking `jti` per target URI "provides a very strong protection…
but it may not always be feasible in practice, e.g., when multiple servers behind a single endpoint
have no shared state" — the RFC explicitly tolerates the single-instance limitation.

### 3.2 What Spring Security 7.1 actually validates

Read from source, not docs. `DPoPProofJwtDecoderFactory` (`org.springframework.security.oauth2.jwt`,
module `oauth2-jose`) plus `DPoPAuthenticationProvider` (module `oauth2-resource-server`):

| Check | Implementation | Notes |
|---|---|---|
| `typ: dpop+jwt` | `DefaultJOSEObjectTypeVerifier` | ✔ |
| `alg` asymmetric, `jwk` present, `jwk` not private | `jwsKeySelector()` | Accepts **only** `JWSAlgorithm.Family.RSA` and `.EC`. **`EdDSA`/Ed25519 is rejected** — which FAPI 2.0 §5.4.1 permits |
| signature vs header `jwk` | Nimbus | ✔ |
| `htm` | `new JwtClaimValidator<>("htm", context.getMethod()::equals)` | **Exact string equality** |
| `htu` | `new JwtClaimValidator<>("htu", context.getTargetUri()::equals)` | **Exact string equality — no RFC 3986 normalization.** The §4.3 SHOULD is not implemented |
| `jti` single-use | `JtiClaimValidator` | `static synchronized LinkedHashMap`, stores **SHA-256 of the jti**, `MAX_SIZE = 1000`, entry expiry **1 hour**, `putIfAbsent != null` → reject |
| `iat` | `new JwtIssuedAtValidator(true)` | `iat` **required**; must be within **±60 s** (`clockSkew` default 60 s, applied symmetrically) |
| `ath` | `AthClaimValidator` (added by the provider) | ✔ |
| `cnf.jkt` binding | `JwkThumbprintValidator` (added by the provider) | Fails with *"jkt claim is required"* if the access token has no `cnf.jkt` |
| `nonce` | — | **Not implemented.** No nonce validator, no `use_dpop_nonce` challenge |

And the plumbing: `DPoPAuthenticationConverter` matches `^DPoP (?<token>…)$` on `Authorization`,
requires exactly one `DPoP` header, and builds the token with
`request.getMethod()` and **`request.getRequestURL().toString()`**.

Five consequences, in descending order of how much they will hurt.

**(a) ~~Enabling DPoP does not disable Bearer.~~ REFUTED BY OBSERVATION, 2026-08-05.**
This section originally claimed — marked `[inferred]`, from reading the wiring without issuing a
request — that `OAuth2ResourceServerConfigurer.configure(H)` always adds
`BearerTokenAuthenticationFilter`, that nothing on the bearer path inspects `cnf`, and that a
DPoP-bound token replayed as `Authorization: Bearer <same token>` would therefore authenticate as an
ordinary JWT. **It does not.** Measured on the resolved stack (Spring Boot 4.1.0 →
**Spring Security 7.1.0**), in an isolated worktree, across a full container and a `@WebMvcTest` slice:

| Request | Observed |
|---|---|
| `cnf.jkt`-bound token as `Authorization: Bearer`, no proof header | **401** `invalid_token` |
| the same token **unbound** (control) | **200**, authenticated |
| bound token as `Bearer` with DPoP **not configured at all** | **401** |
| `cnf.x5t#S256` | **401** — "Unable to obtain X509Certificate" |
| `cnf={}` (empty) | **200** |

The bearer path refuses `cnf`-bound tokens **whether or not DPoP is configured**, and the
`x5t#S256` result shows it is actively resolving the binding method rather than rejecting `cnf`
wholesale — it keys on *recognised* binding methods, not on the claim's presence. Localised by
observation: `NimbusJwtDecoder.decode()` and `JwtAuthenticationProvider.authenticate()` both accept
the bound token in isolation; the refusal happens at the web layer.

Consequences for Plan 3: the one-word downgrade attack does not exist on this version, so no bearer-path
`cnf` validator is needed — the mitigation once proposed here is **redundant**. And "do not configure
bearer resolution at all" is **not expressible as removal**: `BearerTokenAuthenticationFilter` remained
in the chain under every configuration tried; `bearerTokenResolver(request -> null)` is the way to
refuse the scheme if that is ever wanted.

**Still unverified, and it is now the live risk instead:** no successful DPoP request was ever made —
no valid proof was minted — so every DPoP-scheme result above is "proof missing → 401". That the
platform *refuses the wrong thing* is established; that it *accepts the right thing* is not. Plan 3
must prove the DPoP happy path end to end before treating sender-constraining as working. Full detail
and method: `.superpowers/sdd/2026-08-05-overnight/authz-error-mapping-experiment.md`.

**(b) `htu` is exact-match against `getRequestURL()`, which is what the container sees.**
Behind a TLS-terminating proxy, or in compose where the app is reached on a different host/port than
it binds, the client signs `https://api.example/api/v1/…` and the servlet reports
`http://app:8080/api/v1/…`. Every request 401s with `invalid_dpop_proof`. The fix is
`ForwardedHeaderFilter` ordered ahead of the security chain — which collides with spec §6.1's
deliberate rule that *"Client IP is `getRemoteAddr()`, never a raw `X-Forwarded-For`* — the
forwarded-header strategy is enabled only when a trusted proxy fronts the app". Those two are
compatible (both say "enable it when, and only when, a trusted proxy is in front") but they have to
be decided together, once, rather than one being turned on for DPoP and the other assuming it is off.
No normalization also means `https://h:443/x` ≠ `https://h/x` and a trailing slash is a mismatch.

**(c) `jti` replay protection is per-JVM and 1000 entries deep.** `JTI_CACHE` is a static map;
`removeEldestEntry` evicts when `size() > 1000` **or** when the eldest entry has expired. Two
instances behind a load balancer do not share it — which RFC 9449 §11.1 explicitly permits — but
under §9.7's Gatling target of 500 concurrent users the cache also turns over in well under a second,
so the *effective* replay window collapses far below the nominal hour. Neither is a test failure;
both are honest limitations that belong in a documented-assumption row rather than being discovered
later.

**(d) The proof window is 60 s and is not reachable from the DSL.** `JwtIssuedAtValidator`'s
`setClockSkew` is public, but the validator is constructed inside
`DPoPProofJwtDecoderFactory.defaultJwtValidatorFactory()`. Changing it means supplying your own
`jwtValidatorFactory` to a `DPoPProofJwtDecoderFactory`, handing that to
`DPoPAuthenticationProvider.setDPoPProofVerifierFactory`, and constructing the provider yourself —
at which point the `.dPoP()` DSL's convenience is gone. 60 s is a reasonable default, but it makes
container clock drift a hard failure: a laptop resumed from sleep, or a Testcontainers/WSL2 clock a
few minutes off, produces uniform 401s with a message that says nothing about clocks.

**(e) No nonce support.** FAPI 2.0 §5.3.3.1 requires *clients* using DPoP to **support** the
server-provided nonce mechanism; §5.3.2.1 says the AS **may** use it. Spring Security's resource
server neither issues nor validates one. That is conformant for the resource server, but it means the
Python CLI's obligation to handle `use_dpop_nonce` is driven by Keycloak's behaviour, not this app's.

### 3.3 What this means for Plan 4 (Python CLI and Gatling)

Both have to *generate* proofs, so all of §3.2 becomes their problem too. The specific items:

- One **fresh proof per HTTP request**, with a unique `jti` (≥96 bits of randomness or a v4 UUID,
  RFC 9449 §4.2), `htm` = the method, `htu` = the request URL **with the query string stripped** and
  spelled exactly as the server will see it, `iat` = now, and `ath` = base64url(SHA-256(ASCII(access
  token))). A proof cannot be cached across requests: the `jti` single-use check rejects the second
  use, and `htm`/`htu` differ per endpoint anyway.
- `alg` must be `PS256` or `ES256` — **not** Ed25519, which FAPI 2.0 allows and Spring Security
  rejects (§3.2). `ES256` is the cheaper signature; `PS256` matches what the Keycloak profile wants
  for *client assertions*, which is a different signature with a different key and need not be the
  same algorithm.
- Gatling: one ES256 signature per request at 500 concurrent users is real CPU inside the load
  generator. The p99 numbers in §9.7 (write < 150 ms, cached read < 20 ms) were written for a bearer
  client. Whether they are re-baselined with DPoP on, or DPoP is excluded from the load profile and
  proven in a separate correctness test, is a decision, not a detail. A Gatling simulation that signs
  in the virtual-user thread will measure the signer.
- The CLI already generates a movement UID per invocation so its tenacity retries are safe by
  construction (§11). A retried `PUT` must reuse the **movement UID** and mint a **new DPoP proof** —
  same idempotency key, different `jti`. Getting that backwards in either direction produces a bug
  that only shows up on retry.
- Whether the CLI handles `WWW-Authenticate: DPoP … error="use_dpop_nonce"` + `DPoP-Nonce` retry
  depends on whether Keycloak is configured to issue nonces; the resource server never will.

---

## 4. PAR + PKCE S256 + private_key_jwt

The shallowest of the four topics, because for this system most of it does not apply. Stating that
plainly rather than padding it.

**The flow (FAPI 2.0 §5.3.2.2 / §5.3.3.2, RFC 9126, RFC 7636).** The client POSTs the entire
authorization request — `response_type=code`, `client_id`, `redirect_uri`, `scope`,
`code_challenge`, `code_challenge_method=S256`, and its `private_key_jwt` client assertion — to the
pushed-authorization-request endpoint, which authenticates the client and returns a `request_uri`
(RFC 9126 §2.2). The browser is then redirected to the authorization endpoint carrying **only**
`client_id` and `request_uri` (§5.3.3.2). The AS must reject any authorization request that did not
come through PAR, must require `S256` (RFC 7636's `BASE64URL(SHA256(ASCII(code_verifier)))`
comparison, §4.6), must require `redirect_uri` in the pushed request, must return `iss` in the
authorization response per RFC 9207, must issue codes with a ≤60 s lifetime, and must reject a reused
code.

**What changes for a machine client.** Everything above lives under the heading "For flows that use
the authorization endpoint". A client-credentials client — `ledger-cli`, the e2e suite, Gatling —
never visits the authorization endpoint, so **PAR, PKCE, `redirect_uri`, `iss` and the code lifetime
are all irrelevant to it**. What *does* apply to it, from §5.3.2.1/§5.3.3.1:

- `private_key_jwt` client authentication (or mTLS). This is enforced by
  `secure-client-authenticator` in both FAPI 2 profiles — no client secret.
- The assertion's `aud` must be the AS **issuer identifier**, as a **string, not a single-element
  array** (§5.3.3.1; and §5.3.2.1 requires the AS to accept only that). This is the classic
  interop failure — many client libraries emit `"aud": ["https://…"]`.
- Sender-constrained access tokens, i.e. DPoP.
- `PS256`/`ES256`/EdDSA, RSA ≥ 2048 (§5.4.1).
- FAPI 2.0 §5.3.3.1 NOTE 1 is worth noting for a laptop CLI: a client on a machine with a skewed
  clock should sync from the server's HTTP `Date` header when generating assertions.

**Where the two clients diverge, then:** the interactive users (`alice`…`trent`) exercise PAR + PKCE +
consent + the code flow, and only exist to make a browser login work in e2e. The machine client
exercises `private_key_jwt` + DPoP. Half of the FAPI 2 surface is therefore only reachable through a
browser-driven test, which this repo does not have today (Cucumber drives the app in-process;
pytest-bdd drives HTTP). **That is a real gap**: a realm configured for FAPI 2 whose only automated
exercise is a client-credentials call has PAR and PKCE configured but never executed.

**Leaning.** If the goal is "the fixture realm demonstrates something" (review §5), the demonstrable
half is `private_key_jwt` + DPoP through the CLI. PAR/PKCE/consent should either get a browser-driven
test or be recorded as configured-but-unexercised — not quietly counted as done because the realm
JSON contains the flag. My reasoning: this repository's whole documentation posture is that a claim
without a test is not a claim (§9.2b, §10 "Claims without evidence are worse than no claims"), and
that posture applies to realm configuration too.

---

## 5. The `x-fapi-interaction-id` filter

### 5.1 What Open Banking actually requires

Verified first-hand against the v4.0.1 Read/Write Data API Profile, §Headers.

**Request** (optional, all verbs): "`x-fapi-interaction-id` — An RFC4122 UID used as a correlation
Id. If provided, the ASPSP must 'play back' this value in the `x-fapi-interaction-id` response
header."

**Response** (**Mandatory**): "The ASPSP must set the response header `x-fapi-interaction-id` to the
value received from the corresponding fapi client request header or to a RFC4122 UUID value if the
request header was not provided to track the interaction. **The header must be returned for both
successful and error responses.**"

*Source: <https://openbankinguk.github.io/read-write-api-site3/v4.0.1/profiles/read-write-data-api-profile.html>
§Headers → Request Headers / Response Headers.*

Note what the Profile does **not** say: there is no stated behaviour for a client that sends a
malformed (non-RFC-4122) value. The Profile's neighbouring rule for malformed dates is "may respond
400 with error code U003", which suggests a 400 is permissible but not required. Echoing an
unvalidated client string into a response header is a (mild) header-injection and log-injection
surface, so "echo verbatim", "echo only if it parses as a UUID, else mint", and "400 on malformed"
are three defensible readings of the same sentence. This is an open question, not a settled point.

### 5.2 Where it belongs in the chain, and how it meets `traceId`

Four constraints, and they fully determine the position:

1. **It must be on filter-produced responses, not just controller-produced ones.** Once
   `spring-boot-starter-security` is on the classpath, 401s come from an `AuthenticationEntryPoint`
   and 403s from an `AccessDeniedHandler` — both inside the Spring Security filter chain, both
   *outside* the `DispatcherServlet`. A `@RestControllerAdvice` never sees them. So the header cannot
   be set by `ErrorHandlingAdvice`, and the filter must be registered **ahead of** the Spring
   Security filter chain. (In Boot 4.x, `SecurityProperties` no longer exposes the
   `DEFAULT_FILTER_ORDER` constant that older guides quote, so the concrete order value needs
   checking against 4.1 rather than copied from memory.)
2. **The header must be set before `chain.doFilter`, not in a `finally` block.** Setting it after the
   downstream write risks an already-committed response, at which point `setHeader` is a silent
   no-op — which is exactly the case (an error response) the Profile calls out as mandatory.
3. **The MDC must be cleared in a `finally`.** Container threads are pooled; a leaked MDC key makes
   the *next* request's `traceId` wrong, which is worse than absent.
4. It also has to see async dispatches if any handler ever returns a `DeferredResult`/`CompletableFuture`
   (none do today).

**How it relates to `ErrorHandlingAdvice`'s `traceId`.** These are two different identifiers doing
two different jobs, and conflating them is the tempting mistake:

| | `x-fapi-interaction-id` | `traceId` |
|---|---|---|
| Chosen by | the **client** (or minted by us) | the **tracer** (W3C `traceparent`, §6.6) |
| Format | RFC 4122 UUID | whatever the OTel exporter uses (hex trace id) |
| Where it appears | response header, every response | inside the problem body, error responses only |
| Purpose | client-side correlation across a retry | server-side correlation into Tempo |

`ErrorHandlingAdvice.traced()` reads `MDC.get("traceId")` and today always finds nothing —
"Plan 3 wires the tracer" (line 118). Two things could satisfy that comment and they are not the
same:

- **(i)** Micrometer Tracing's OTel bridge, which populates the MDC with `traceId`/`spanId` from the
  current span. This is what §6.6 describes and it is step 9 in §14's order, *after* step 8. If Plan
  3 is step 8 only, `traceId` stays empty and the comment stays true-but-unfulfilled.
- **(ii)** The interaction-id filter putting its own value into the MDC under some key. If it uses the
  key `traceId`, the problem body's `traceId` silently becomes the interaction id, and when step 9
  lands there are two writers to one key. If it uses `interactionId`, then problem bodies carry a
  `traceId` that is still empty until step 9, and the two ids coexist cleanly — at the cost of the
  correlation the OB review sold ("gives 2xx responses the correlation `traceId` only gives errors")
  being a *different* id from the one in error bodies.

**Leaning.** Separate keys, and bind the interaction id to the current span as an attribute
(`ledger.fapi_interaction_id`, matching §6.6's `ledger.*` prefix convention) rather than overloading
`traceId`. Reasoning: §6.6 already fixes what `traceId` means (the tracing backend's id, with
exemplars pointing at it), and one key with two possible meanings depending on which step has landed
is precisely the kind of thing that reads as green and behaves as wrong. But this hands the council a
consequence to accept: until step 9, error bodies have no `traceId`, and the OB review's stated
benefit is only half-delivered.

---

## 6. How RBAC lands on the existing seams

This is the part with the most repository-specific content and the most that is genuinely unresolved.

### 6.1 What the ArchUnit rules do and do not forbid

`src/test/java/com/ffroliva/tinyledger/architecture/HexagonalRulesTest.java`, all eight rules, read
literally:

| Rule | Forbids | Does **not** forbid |
|---|---|---|
| `domainIsFrameworkFree` | `..domain..` (except `package-info`) depending on `org.springframework..`, `jakarta.persistence..`, `org.apache.kafka..`, `io.lettuce..` | **Anything outside `..domain..`.** `..application..` may freely *import* Spring types |
| `applicationCarriesNoSpringAnnotations` | `..application..` classes annotated `@Service`, `@Component`, `@Transactional` | **`@PreAuthorize`, `@Secured`, `@RolesAllowed`, `@PostAuthorize`, `@EnableMethodSecurity`.** None of them is on the list |
| `adaptersNeverCallAdapters` | `..adapter.out.(*)..` slices depending on each other | — |
| `onlyConfigInstantiatesOutboundAdapters` | anything outside `..config..`/`..adapter.out..` depending on `..adapter.out..` | Depending on `application.port.out` **interfaces** — which is why `AuditController` → `AuditTrailPort` is legal today |
| `noCyclicPackages` | cycles between top-level slices (`audit`, `balance`, `config`, `ledger`, `notification`, `platform`, `shared`, `api`) | — |
| `noServiceDependsOnAnotherService` | `..application.usecase..` → `..application.usecase..` | — |
| `generatedDtosStayInWebAdapters` | non-`adapter.in.web` code depending on `api.generated..` | — |
| `domainNeverCallsNowOrRandomUuid` | `..domain..` calling `Instant.now`/`UUID.randomUUID` | — |

Two prose-vs-test gaps worth naming, because Plan 3 is where they matter:

- **§6.4 forbids `@PreAuthorize` on an application service in prose; the test does not.** The spec
  says a decorator is used "because `@PreAuthorize` on an application service would put a framework
  annotation exactly where §9.2 forbids one" — but §9.2's implemented rule enumerates three
  annotations and `@PreAuthorize` is not among them. If the decorator design is chosen, extending
  `applicationCarriesNoSpringAnnotations` to cover the security annotations is what turns the prose
  into a guarantee. If method security is chosen instead, the rule needs no change at all — which is
  a slightly uncomfortable asymmetry to be aware of when reading the rule as evidence.
- **§3 claims "an ArchUnit rule keeps services and repositories out of `shared`".** There is no such
  rule in `HexagonalRulesTest`. Relevant because "put the caller/principal abstraction in `shared`"
  is an obvious move and nothing currently stops it.

### 6.2 The seam inventory — which ports a decorator can even authorise

| In-port | Caller term today | Owner reachable from? | A decorator could check |
|---|---|---|---|
| `OpenAccountUseCase.open(OpenAccount)` | `OpenAccount.caller` | n/a — no owner until it exists | role only |
| `RecordMovementUseCase.deposit(Deposit)` / `.withdraw(Withdraw)` | `Deposit.caller` / `Withdraw.caller` | event stream | role + owner |
| `QueryStrongBalanceUseCase.strongBalance(String caller, AccountId)` | explicit `caller` parameter | event stream | role + owner |
| `QueryBalanceUseCase.balance(AccountId)` | **none** | — | **role only** — signature must change |
| `QueryHistoryUseCase.history(AccountId, HistoryQuery)` | **none** | — | **role only** — signature must change |
| `QueryAccountsUseCase.accountsOwnedBy(String owner)` | `owner`, which *is* the filter | inherent | role; ownership is structural |
| audit `getEvents` / `listAuditEntries` | **no in-port at all** | n/a (role-only check) | **nothing — there is no seam** |

Three findings sit in that table.

**m2 is real and it is not the only one.** The parked finding records that `audit` has no
`QueryAuditTrailUseCase`: `AuditController` holds `Optional<AuditTrailPort>` — the *out*-port — and
calls it directly (`AuditController.java:47,61,84`). Its own Javadoc says as much: "§6.4's
`ledger:auditor` check is the composition root's authorisation decorator, which arrives with
Keycloak". A decorator has nothing to wrap. But **`QueryBalanceUseCase` and `QueryHistoryUseCase`
have the same problem in a different shape** — the seam exists, the *caller* does not. And unlike
m2, that one is undocumented.

**Two of the three balance reads are currently unauthorised, and one is authorised by filtering.**
`getBalance` and `listTransactions` do no ownership check at all — `BalanceController.java:69-75,
100-112` pass only the `AccountId`. `listAccounts`/`getAccount` are authorised *by construction*:
they call `accountsOwnedBy(CALLER)` and, for the single-account case, filter the result
(`BalanceController.java:81,90-94`). That is an ownership **filter**, not an ownership **check** —
which happens to be exactly the shape that composes cleanly with the admin proposal's "widen the
ownership term", and exactly the shape that makes N12 (list scoping) true without a comparison.

**Where the write-path ownership check already lives.** `RecordMovementService.record()`, in order:
① `store.read(accountId)` → ② `Account.rehydrate` → ③ `if (!account.owner().equals(caller)) throw
new OwnershipException(...)` → ④ `store.findByMovementUid(movementUid)`, with the source comment
`// ④ (after authz)`. `StrongBalanceService` does the same read/rehydrate/compare. So §6.3's
authorise-before-idempotency is **already implemented and already correct**, inside the application
service, and §4.1's step 2/step 3 ordering is a property of that method body.

### 6.3 The three candidate shapes

**Shape A — a decorator per in-port in `config`, mirroring `TransactionalUseCases`.** What §6.4 and
§4.5 describe. Concretely it needs:

1. **A caller term on `QueryBalanceUseCase` and `QueryHistoryUseCase`.** Signature changes on two
   inbound ports, plus their services, their tests, and `BalanceController`.
2. **A new inbound seam in `audit`** — `QueryAuditTrailUseCase` in `audit.application.port.in`, a
   service in `audit.application.usecase` delegating to `AuditTrailPort`, and `AuditController`
   rewired to the in-port. Note this service is a pure pass-through whose only reason to exist is to
   give the decorator something to wrap; a reviewer who has not read m2 will read it as ceremony.
   Note also that `AuditController`'s `Optional<AuditTrailPort>`-is-standalone trick
   (`available()` → 501) has to survive the move: either the in-port bean is absent in `standalone`
   and the controller keeps an `Optional<QueryAuditTrailUseCase>`, or the 501 moves inside the
   service, which puts a `full`-vs-`standalone` distinction into `application`.
3. **A source of roles.** `SecurityContextHolder` is a Spring type; `config` may use Spring freely
   (it already does). No rule blocks it.
4. **A source of the owner.** Three sub-options, and this is the substantive one:
   - **(4a) The decorator re-reads the stream.** `EventStorePort.read(accountId)` in the decorator
     means a **second full replay per write**, on top of the one the service already does. Two
     replays per movement against §9.7's p99 < 150 ms budget.
   - **(4b) The decorator does the role half; the service keeps the ownership half.** Zero new
     reads, and the ownership check stays where §4.1's step 2 already puts it. But then the spec's
     sentence "Ownership is checked against the JWT subject, **inside that wrapper**" is not what the
     code does, and the admin clause has to be threaded into the *service*, which needs the caller's
     roles — pushing a roles concept into `application`.
   - **(4c) A narrow ownership out-port** — `AccountOwnerPort.ownerOf(AccountId)`, one row
     (`version = 1`'s `owner`) rather than a replay. One new port, one method per mode, and it makes
     ownership queryable without rehydrating. Also the only option that lets a `balance` query be
     authorised without either module learning about the other, because the port is named from
     `config`.

**Shape B — keep the check in the application service (status quo, extended).** Cheapest by a wide
margin: `RecordMovementService` and `StrongBalanceService` already do it; `BalanceQueryService` and
`HistoryQueryService` would gain a caller and the same three lines; `audit` gains a role check
somewhere. Costs: the role term has to reach `application`, and §6.4's decorator sentence becomes
false. Note the roles could arrive as a plain `Set<String>`/`boolean isAdmin` on the command — no
framework type crosses the boundary, and no ArchUnit rule is touched.

**Shape C — Spring method security (`@PreAuthorize`).** Council-closed (§6.4, §9.2, and the admin
proposal's D5 closes it a second time). Recorded here only because §6.1's table shows the ArchUnit
rule as written would **not** catch it, so "the build enforces this" is currently untrue for this
specific prohibition. Also: the ownership half needs the event stream, which the web layer does not
have — so C could only ever cover the role half anyway.

### 6.4 How the admin clause composes

The approved proposal (D1) makes the rule *operation role* **AND** (*subject is owner* **OR** *caller
holds `ledger:admin`*), and is explicit that `ledger:admin` widens the ownership term only. That
composes cleanly onto every seam **except the two list-shaped ones**, and the proposal does not
address them:

- `getAccount(accountUid)` is implemented *through* `accountsOwnedBy(CALLER)` plus a filter
  (`BalanceController.java:90-94`). Widening the ownership term for `getAccount` therefore cannot be
  done by widening that call, because the same call backs `listAccounts`.
- So: **what does `GET /api/v1/accounts` return for `trent`?** He owns nothing (proposal D6). Three
  answers, all consistent with the proposal's words and mutually exclusive: an empty list (ownership
  term unwidened for lists); every account in the realm (ownership term widened, N12's scoping off
  for admins); or a 403 (lists are not an on-behalf-of operation). The third has a consequence: §11's
  CLI resolves `--account ACC-001` by name through `GET /api/v1/accounts`, so an admin who cannot
  list cannot use the CLI's name resolution at all, and an admin who lists the realm gets name
  ambiguity across every customer.
- `ledger:auditor` is a **pure role check** (D2: admin is not an auditor, and N13 is the scenario
  that catches a blanket bypass). This is the one place where the decorator needs no ownership term
  at all — which is also why an audit seam that only ever carries a role check is the cheapest
  decorator in the set, once the seam exists.

One more composition detail the proposal implies but does not state: D3 has the **use case** stamp
`actor` onto emitted events, while the **decorator** performs the ownership comparison. So the caller
principal has to be available in both places — which it already is (§2.4, and every command record
carries it). No new plumbing; worth confirming rather than rediscovering.

### 6.5 Mechanical hazards in the composition root

Four things that will bite whichever shape is chosen.

**(a) `@Primary` is already spent on the two write ports.** `FullAdapterConfig` declares
`@Bean @Primary OpenAccountUseCase transactionalOpenAccount(OpenAccountService)` and the same for
`RecordMovementUseCase` (lines 145-155). Adding an authz decorator as a second bean of the same
interface means either one bean that nests both wrappers, or `@Primary` moving and the inner bean
being injected by name/qualifier. Nesting in one `@Bean` method is the smaller diff.

**(b) `UseCaseConfig` returns concrete types for the two write use cases and interface types for the
four query use cases.** `openAccount`/`recordMovement` return `OpenAccountService`/
`RecordMovementService` specifically so "the `full` profile wraps these in a transactional decorator…
and needs to inject the undecorated service unambiguously" (`UseCaseConfig.java:25-37`). The query
beans return `QueryStrongBalanceUseCase`, `QueryBalanceUseCase`, `QueryHistoryUseCase`,
`QueryAccountsUseCase`. **Decorating a query port therefore requires changing `UseCaseConfig`** — the
profile-independent file, and the same seam the overnight queue excluded P9 from touching because it
"changes profile bean composition, the seam the whole two-mode design rests on". This is not a
blocker; it is the reason the query-side decorator is more invasive than the write-side one, and it
should be priced that way.

**(c) The `AuthorizationConfig.STANDALONE_PRINCIPAL` constant is load-bearing in a way that is
invisible. [inferred]** Both controllers hold `private static final String CALLER =
AuthorizationConfig.STANDALONE_PRINCIPAL`. `javap` on `target/classes/.../BalanceController.class`
shows the value inlined as `ldc // String local` — a compile-time constant, folded per JLS §13.1.
`config` already depends on `balance` and `ledger`, so if ArchUnit recorded a `balance → config` edge
there would be a two-slice cycle and `noCyclicPackages` would fail; the suite is green, so it does
not record one. **The moment the caller stops being a folded constant** — a `config`-hosted
`CallerPort`, a `SecurityContextHolder` helper in `config`, anything the controller *calls* rather
than *inlines* — that edge becomes real and the cycle rule fails. `platform` is no refuge either:
`ErrorHandlingAdvice` gives `platform → ledger`, so a `platform`-hosted caller source that `ledger`
called back would be a `platform ↔ ledger` cycle. Marked inferred: I read the bytecode but could not
run ArchUnit to confirm which way it resolves.
*The cheap way out:* let the web adapter source the principal from Spring MVC's own argument
resolution (`Authentication` / `@AuthenticationPrincipal Jwt` in `adapter.in.web`, which no rule
forbids) and keep stamping the command exactly as it does today. No new package edge appears at all.

**(d) If the decorator trusts `cmd.caller()`, a controller bug is an authorisation bypass.** The
decorator can either trust the command (smaller, and the command is built two lines away) or assert
`cmd.caller().equals(authentication.getName())` (one line, closes the class of bug where a controller
stamps the wrong principal). Worth an explicit decision rather than an accident.

### 6.6 Two secondary consequences of adding Spring Security at all

- **`spring-boot-starter-security` is not profile-scoped.** Put it on the classpath and Boot's
  default `SecurityFilterChain` secures *every* endpoint in **both** modes, including `standalone`,
  with a generated password. §1's whole promise is that `standalone` is take-home-runnable with no
  credentials. So Plan 3 needs a `@Profile("standalone")` chain that permits everything — and that
  chain is now a piece of security-relevant configuration that only exists to *disable* security,
  which is exactly the sort of thing `FailClosedGuard` was written to guard against. The converse
  guard (§1: nothing refuses to start `full` without an issuer-uri) becomes worth having at the same
  time.
- **`ErrorHandlingAdvice`'s catch-all swallows `AccessDeniedException` into a 500. CONFIRMED BY
  OBSERVATION, 2026-08-05** — measured on Spring Security 7.1.0 across three observation modes (full
  container with the default chain, full container with an explicit `permitAll` chain proving
  `ExceptionTranslationFilter` is present, and a `@WebMvcTest` slice). All three agreed:

  | Thrown from inside the use case | Status | Body |
  |---|---|---|
  | `AccessDeniedException` | **500** | opaque, no `type`/`detail` |
  | `AuthorizationDeniedException` (exists in 7.1.0, extends the above) | **500** | opaque — no distinct mapping |
  | `@PreAuthorize` denial, method security enabled | **500** | opaque |
  | `OwnershipException` | **403** | full, `type=/errors/forbidden` |

  The advice logged `"unhandled exception at the API boundary"` exactly 12 times — 4 scenarios × 3
  modes — so it is demonstrably the advice claiming these, inside `DispatcherServlet`, before
  `ExceptionTranslationFilter` can see them. **`@Order(Ordered.HIGHEST_PRECEDENCE)` is NOT load-bearing
  for this**: removing it changed nothing. (It was not re-checked against the validation-400s it may
  exist to protect, so leave it in place.)

  **Design consequence, now evidence-backed rather than argued:** the authorization decorator must throw
  this codebase's own exception types, never Spring's. `OwnershipException` already maps to
  `403 /errors/forbidden`, and it keeps working with Security on the classpath. Throwing Spring's
  `AccessDeniedException` from a port decorator would return an opaque **500** to a correctly-denied
  request — a denial indistinguishable from a server fault, which is both a usability defect and an
  information-disclosure oddity in the wrong direction. This also means the error-catalogue work
  (`TinyLedgerException` + `ErrorCode`, with a `FORBIDDEN` code) belongs **with or before** the authz
  work, not after it.

---

## 7. Ordering constraints

### 7.1 Authorise-before-idempotency is already satisfied, and will stay satisfied

§6.3: "Replays are answered only after ownership of the path account passes (§4.1) — idempotency is
never an authorisation bypass." §4.1: step 2 ownership, step 3 movement-UID lookup.

Today that ordering is a property of `RecordMovementService.record()` (③ before ④, §6.2 above). If
authorisation moves to a decorator, it necessarily runs before the entire service body, hence before
the UID lookup — the constraint becomes *structurally* impossible to violate rather than
*conventionally* satisfied. **So §6.3's ordering rule is not the hard part of Plan 3.** It is worth
saying so explicitly, because the constraint is prominent enough in the spec to look like the main
event, and the actual difficulty is elsewhere (§6.2, §6.5).

One subtlety worth keeping: the UID lookup is **global** (§6.3, "reusing a UID against a *different
account* is a `409`"). Because ownership of the **path** account is checked first, a foreign caller
gets a 403 and never learns whether the UID exists on some other stream. Both orderings must be
preserved together — ownership of the path account, *then* the global lookup — and that is exactly
what the current code does.

### 7.2 The real ordering question is authz vs the transaction

`full` composes `Transactional(Service)`. Adding authz gives two arrangements and they are not
equivalent:

| Arrangement | For | Against |
|---|---|---|
| `Transactional(Authz(Service))` | The decorator's ownership read and the service's read are the **same transaction** and therefore the same snapshot. One connection. | Every refused request opens and rolls back a DB transaction. An unauthenticated or cross-account probe now consumes a pool connection — small, but it is amplification against §6.1's rate limits, and the refusals are the requests an attacker controls the volume of |
| `Authz(Transactional(Service))` | A 403 costs no transaction and no connection | The authz ownership read happens **outside** the transaction the service later reads in, so ownership could in principle change between them |

The second row's race is **currently unrealisable**, because ownership is written once by
`AccountOpened` at version 1 and there is no ownership-transfer event. But that is a property of the
*domain*, not of the *mechanism* — and the admin proposal deliberately does not add a transfer event,
so it holds for now. If it is relied on, it should be recorded as a documented assumption (§15) so a
future ownership-transfer feature trips over the statement instead of the bug.

Two related ordering items that land in the same step 8:

- **Rate limiting spans the authentication boundary.** §6.1 has per-principal buckets (100/min
  writes, 1000/min reads) *and* per-IP buckets for unauthenticated traffic (20/min) plus an
  any-traffic backstop (300/min). Per-principal needs the authenticated principal, so it must run
  after the security chain; the unauthenticated per-IP bucket must run before. That is either two
  filters at two positions or one filter after the chain that also handles the anonymous case — and
  it competes with §5.2's requirement that the interaction-id filter be *ahead* of the chain, so all
  three orders should be decided in one sitting.
- **The 429's `Retry-After` and the 401/403 problem bodies are all filter-produced**, so §6.5's
  catalogue rows for `/errors/rate-limit-exceeded`, `/errors/unauthenticated` and
  `/errors/forbidden`-by-role cannot be served by `ErrorHandlingAdvice`. Something has to write
  `application/problem+json` from inside the filter chain, and it has to agree with the advice
  exactly, or §6.5 stops being one authority.

---

## 8. Open questions

Every decision I found, as a question. No recommendations — where I have a leaning it is marked in
the body section named.

**Standards posture**

1. **FAPI 2 or FAPI 1 Advanced?** (Carried from the OB review §7.2, unresolved.) FAPI 2 is the
   better engineering target and what Keycloak ships as a profile; FAPI 1 Advanced is what OBL
   v4.0.1 actually mandates, so it is the only one that is "OB compliant". They are mutually
   exclusive at realm-config level. Trade-off: conformance to an ecosystem this app is not in, versus
   a security profile that has been formally analysed and is where new deployments are going.
2. **DPoP or mTLS for sender-constraint?** FAPI 2.0 §5.3.4 permits either. Note that this is not
   just a cost question: `fapi-2-security-profile` includes `holder-of-key-enforcer` and therefore
   *requires* mTLS, so choosing DPoP also chooses `fapi-2-dpop-security-profile`. Trade-off: a config
   flag versus a certificate lifecycle in a compose file.
3. **How much of FAPI is exercised versus configured?** PAR, PKCE and consent are only reachable
   through a browser-driven login, which this repo has no harness for. Options: build one; accept
   configured-but-unexercised and record it; or scope Plan 3's FAPI work to the machine-client half
   (`private_key_jwt` + DPoP) and defer the interactive half. Trade-off: a new test harness versus a
   compliance claim with no test behind it.

**DPoP specifics**

4. ~~**Does the resource server refuse plain `Bearer` in `full`?**~~ **ANSWERED BY OBSERVATION,
   2026-08-05: yes, it already does.** Spring Security 7.1.0 returns 401 for a `cnf`-bound token
   presented on the bearer path, with or without DPoP configured (§3.2a). No decision needed and no
   mitigation to build. **The replacement question, which is now the real one:** does the DPoP *happy
   path* work end to end? The experiment never minted a valid proof, so only the refusal is proven,
   not the acceptance. Plan 3 needs a positive test with a real proof before sender-constraining can be
   called working — and until that exists, "bound tokens are refused everywhere" is indistinguishable
   from "sender-constraining works".
5. **Is the forwarded-header strategy on?** `htu` is compared by exact equality against
   `request.getRequestURL()`. Off behind a TLS-terminating proxy, every DPoP request 401s; on without
   a trusted proxy, clients can spoof past §6.1's per-IP bucket. Both spec sections have to agree on
   one answer.
6. **What is the accepted `jti` replay guarantee?** Spring Security's cache is per-JVM, 1000 entries,
   1 hour. Options: accept it and record it as a documented assumption (RFC 9449 §11.1 explicitly
   tolerates the multi-instance case); or back it with Redis, which the stack already runs. Trade-off:
   an honest limitation in writing versus a shared store and a second thing that can be down.
7. **Do the §9.7 load thresholds get re-baselined with DPoP on?** One asymmetric signature per
   request inside the load generator at 500 users. Options: re-baseline; exclude DPoP from the load
   profile and prove it in a correctness test; or leave the thresholds and let the first Gatling run
   settle it.

**`x-fapi-interaction-id`**

8. **What happens to a malformed client value?** The Profile says only "play back this value". Echo
   verbatim (header/log-injection surface), echo only if it parses as RFC 4122 else mint, or 400. The
   Profile permits at least the first two and does not forbid the third.
9. **Does the interaction id share the MDC key with `traceId` or not?** (§5.2. My leaning is
   separate keys.) Trade-off: one key means error bodies get a correlation id immediately but the id
   changes meaning when step 9 lands; two keys mean error bodies carry no `traceId` until step 9.

**RBAC shape**

10. **Which shape — decorator (A), service-internal (B), or method security (C)?** §6.3. The spec
    says A; B is what the code already does for three of the seven seams and is much the cheapest;
    C is council-closed twice but is the only one the current ArchUnit rule would not catch.
11. **If A: where does the owner come from?** Re-read the stream in the decorator (two replays per
    write), leave the ownership half in the service (and the spec sentence becomes false), or add a
    narrow `AccountOwnerPort` (one new port, one adapter method per mode). §6.3 item 4.
12. **Does `audit` get a real use case or a pass-through?** m2 requires an inbound seam for a
    decorator to wrap. A pass-through service exists only to be decorated. And the
    `Optional<AuditTrailPort>`-is-standalone 501 trick has to survive the move without putting a
    profile distinction into `application`. §6.3 item 2.
13. **What do `QueryBalanceUseCase` and `QueryHistoryUseCase` gain?** They have no caller term at
    all, so `GET /balance` and `GET /transactions` are unauthorised today. A caller parameter, or a
    command-shaped value object, or (if shape B) the check inside. This is m2's sibling and it is not
    yet written down anywhere.
14. **What does `GET /api/v1/accounts` return for an admin?** Empty list, the whole realm, or 403 —
    all three are consistent with the approved proposal's wording, and the answer changes what §11's
    CLI name resolution does for an admin. §6.4.
15. **Does the decorator trust the command's caller, or assert it against the security context?**
    §6.5d.
16. **Which arrangement: `Transactional(Authz(...))` or `Authz(Transactional(...))`?** §7.2. And if
    the latter, is "ownership is immutable" recorded as a documented assumption?
17. **Does `applicationCarriesNoSpringAnnotations` get extended to the security annotations?** §6.1.
    Only needed if shape A or B is chosen — which is itself a slightly odd property for a rule that
    is supposed to be evidence for the decision.
18. **Does `full` get a fail-closed guard?** `FailClosedGuard` protects one direction only; a `full`
    boot with no `issuer-uri` is currently unguarded. §1, §6.6.
19. **How do 401/403/429 problem bodies get written from inside the filter chain** so that §6.5
    stays one authority rather than two implementations that agree by inspection? §7.2.

**Spec mechanics**

20. **Spec version numbering.** HANDOFF §2 says the admin proposal must be renumbered to **v3.9**
    (v3.8 was consumed by the Plan 2 close-out), but the proposal still says 3.8 in five places:
    its header ("Target spec version: 3.8"), D7's title, §3.12's assumption 9 ("predates v3.8"),
    §3.13's revision-history row, and the §4 impact table. Whether the OB §7.2 table lands in the
    same revision or its own is also still open (OB review §7 question 4).

---

## 9. Cost and risk

**Cheap, and genuinely cheap.**

- The `x-fapi-interaction-id` filter. One filter, ~30 lines, and the only hard parts are ordering
  (§5.2) and picking an MDC key (Q9). The review's "~2 hours" is right.
- The realm JSON's *structural* content: a `clientPolicies` block naming a global profile. It is
  data, and Keycloak ships the profile.
- The audit-side authz check itself. `ledger:auditor` is a pure role predicate with no ownership
  term — the simplest decorator in the set, *once the seam exists*.

**Deep, and deeper than the review priced it.**

- **`private_key_jwt` for the CLI.** `secure-client-authenticator` refuses client secrets, so §11's
  one-line "OAuth2 client-credentials against Keycloak" becomes key management, JWT assembly, PS256
  signing, and the `aud`-as-a-string trap. Priced nowhere today. This lands in Plan 4 but is created
  by a Plan 3 decision.
- **The seam work that has to exist before any decorator can.** A new inbound port and service in
  `audit`; a caller term on two `balance` in-ports; `UseCaseConfig` return types changed for the
  query beans (§6.5b — the seam the overnight queue explicitly refused to touch unsupervised); and
  the `@Primary` composition rearranged. None of it is hard. All of it is in the files the two-mode
  design rests on.
- **DPoP in the Python CLI and in Gatling.** A fresh signed proof per request, with `htu` spelled
  exactly as the server sees it. The CLI is a day; the Gatling signing cost and what it does to the
  §9.7 thresholds is the unknown.

**Where I think the real difficulty is** — and none of the three is on the review's list:

1. ~~**The Bearer downgrade (§3.2a, Q4).**~~ **REFUTED 2026-08-05** — Spring Security 7.1.0 already
   401s a `cnf`-bound token on the bearer path, with or without DPoP configured, so there is no
   validator to write. **What replaces it as the top risk is the mirror image:** the experiment proved
   only that bound tokens are *refused* on the wrong scheme, never that they are *accepted* on the
   right one, because no valid DPoP proof was ever minted. Until a positive end-to-end DPoP test
   exists, "sender-constraining works" and "bound tokens are rejected everywhere, so DPoP is broken"
   produce an identical green suite. The `mallory`-grade negative is still worth having as a
   regression guard — *valid bound token, correct role, correct owner, wrong scheme* — but it must be
   paired with a positive, or it passes for the wrong reason.
2. **`full_scope_disabled` (§2.2).** The FAPI profile turns off automatic realm-role inclusion. The
   symptom is every authorisation test failing at once, and the cause looks exactly like a broken
   decorator. Expect to lose an afternoon to it if nobody has read the executor list first — which
   is why the list is in §2.2 verbatim.
3. **Deciding where ownership is checked (Q10/Q11) rather than drifting into it.** The code already
   checks ownership correctly, in the right order, in three services. The spec says a decorator does
   it. Those are two different designs and both are defensible; what is not defensible is ending up
   with the check in both places, or with the spec describing one and the code doing the other — the
   exact condition CR14 was raised to fix for `@Externalized`, eight weeks ago, in this same
   document set.

A closing note on the shape of the risk. Spec §6.4's `mallory` paragraph says it best: "A test suite
without a `mallory` proves authentication and nothing about authorisation." Plan 3 adds two more
boundaries with the same property — the admin clause (which `trent`'s N13 covers, per the approved
proposal) and token binding (which nothing covers yet). Both are boundaries whose positive paths stay
green under a wrong implementation. That is the whole reason HANDOFF §0 says not to start
implementing before the plan is reviewed, and it is the reason this document resolves nothing.

---

## Sources

**Specifications**

- RFC 9449, OAuth 2.0 Demonstrating Proof of Possession (DPoP) — §4.2 (claims), §4.3 (checking
  proofs), §6 (`cnf.jkt`), §7.1 (the DPoP authentication scheme), §8–9 (nonce), §10.1 (authorization
  code binding), §11.1 (proof replay). <https://www.rfc-editor.org/rfc/rfc9449.txt>
- RFC 9126, OAuth 2.0 Pushed Authorization Requests — §2.2. <https://www.rfc-editor.org/rfc/rfc9126.txt>
- RFC 7636, PKCE — §4.6 (`S256` verification). <https://www.rfc-editor.org/rfc/rfc7636.txt>
- FAPI 2.0 Security Profile (Final, February 2025) — §5.3.2.1 (authorization servers, general),
  §5.3.2.2 (authorization endpoint flows), §5.3.3.1/§5.3.3.2 (clients), §5.3.4 (resource servers),
  §5.4.1 (cryptography). <https://openid.net/specs/fapi-security-profile-2_0-final.html>
- UK Open Banking Read/Write Data API Profile v4.0.1 — §Headers → Request Headers / Response Headers.
  <https://openbankinguk.github.io/read-write-api-site3/v4.0.1/profiles/read-write-data-api-profile.html>

**Implementations** (read on 2026-08-05)

- Keycloak global client profiles: `services/src/main/resources/keycloak-default-client-profiles.json`
  on `keycloak/keycloak@main` — the executor lists in §2.2 are quoted from it.
- Keycloak Server Administration Guide, §Client Policies.
  <https://www.keycloak.org/docs/latest/server_admin/index.html>
- Keycloak, Securing applications with OpenID Connect, §Financial-grade API (FAPI) Support.
  <https://www.keycloak.org/securing-apps/oidc-layers>
- Keycloak release notes (26.7.0 current; FAPI 2 Final and supported DPoP in 26.4.0).
  <https://www.keycloak.org/docs/latest/release_notes/index.html>
- "Official Support for DPoP in Keycloak 26.4". <https://www.keycloak.org/2025/10/dpop-support-26-4>
- Spring Security `main` (7.1.x): `OAuth2ResourceServerConfigurer` (the `dPoP` DSL and
  `DPoPConfigurer`, `@since 7.1`), `DPoPAuthenticationProvider` (`@since 6.5`),
  `DPoPAuthenticationConverter` (`@since 7.1`), `DPoPProofJwtDecoderFactory` and
  `JwtIssuedAtValidator` (both in `oauth2-jose`).
- Spring Boot managed versions: `spring-boot-dependencies-4.1.0.pom` (Maven Central) —
  `spring-security.version` 7.1.0.

**This repository**

`docs/spec.md` v3.8 §2.3/§2.4/§3/§4.1/§4.4/§4.5/§6.1/§6.3/§6.4/§6.5/§6.6/§7/§9.2/§9.2b/§9.3/§9.7/
§11/§14 · `HANDOFF.md` §2 · `docs/adr/0001-kafka-delivery-path.md` ·
`docs/superpowers/plans/2026-08-04-spec-admin-on-behalf-of-proposal.md` ·
`docs/superpowers/plans/2026-08-04-open-banking-standards-review.md` §5–§7 · `pom.xml` ·
`docker/docker-compose.yml` · `src/main/resources/application*.properties` · `docs/api/openapi.yaml` ·
`config/{AuthorizationConfig,UseCaseConfig,StandaloneAdapterConfig,FullAdapterConfig,TransactionalUseCases}.java` ·
`platform/{ErrorHandlingAdvice,FailClosedGuard}.java` ·
`ledger/adapter/in/web/LedgerController.java` · `balance/adapter/in/web/BalanceController.java` ·
`audit/adapter/in/web/AuditController.java` · `audit/application/port/out/AuditTrailPort.java` ·
`ledger/application/usecase/{RecordMovementService,StrongBalanceService}.java` ·
`ledger/application/port/in/*` · `balance/application/port/in/*` ·
`balance/adapter/in/events/LedgerEventsListener.java` ·
`src/test/java/com/ffroliva/tinyledger/architecture/HexagonalRulesTest.java`

*No code, spec, contract or configuration was modified in producing this document.*
