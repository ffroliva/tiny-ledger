# Documentation index

Routing table. **Read on need, not on principle** — this exists so an agent can find the one authority
that answers the question in front of it, rather than reading 200KB of specification to change a filter.

The rules you need *before* touching anything are not here — they are in **`../AGENTS.md`** (source of
truth, vendor-neutral; every tool's convention file routes to it).

Quadrants are Diátaxis, per spec §8.1.

## Read this when…

| Read | When | Quadrant |
|---|---|---|
| **`../AGENTS.md`** | **Always, first.** The gates, the enforced rules, the traps already paid for | — |
| `../README.md` | Running it for the first time — `standalone`, one command, no Docker | Tutorial |
| **`docker.md`** | **Running the `full` profile: build the image, start the stack, get a token, move money, tear down.** A runbook of verified commands, plus a symptom→cause table for the responses that look like faults and are not (`403` on the health root, a **refused connection on 8080/9090 now that neither is published**, `401` from an issuer mismatch, and a Windows `curl` that will not accept the dev CA) | Tutorial / How-to |
| **`ledger-cli.md`** | **Using the Python CLI** — installing it with `uv`, how it gets a token in `full` (Direct Access Grants, and the fixture users with their roles), worked deposit/withdraw/balance examples, the seven `scenario run` sequences and what each proves, and a symptom→cause table. Also the honest list of what it does *not* do: `--json` parses and is ignored, there is no `ledger-cli` service account, nothing is seeded | Tutorial / How-to |
| **`urls-and-tls.md`** | **Which URLs exist, which are public, which are internal-only, what is encrypted and where the encryption stops — and how to run WITHOUT TLS.** One place for a map that was previously spread across a Compose file, two runbooks and a properties file, which is how five of its facts ended up disagreeing. Also the port variables that are not free knobs | Explanation / Reference |
| **`pitfalls.md`** | **The runtime failures that cost hours, grouped by the symptom you actually see** — every 401 whose real cause is a certificate, the Windows curl that cannot take a private CA, Traefik serving a certificate you did not generate, rate limits that fire for no reason, and the things that look broken and are correct. `AGENTS.md` covers the build-and-test equivalents; this is the runtime half | How-to / Reference |
| **`security-material.md`** | **Adding or looking for any credential, key or certificate.** What exists today and where each is injected, why the Keycloak fixture password is public on purpose, the one key still in git history and why no rotation is owed — and **how TLS actually works here** — Traefik terminating at the edge, a dev CA generated on demand and never committed, the `X-Forwarded-For` trust that keeps §6.1's per-IP backstop from being bypassed, and the hops that are still plaintext | Explanation / Reference |
| **`kubernetes.md`** | **Deploying to Kubernetes (local Kind, AWS EKS, Azure AKS)**, Kustomize packaging, and LocalStack offline IaC validation | Tutorial / How-to |
| **`ai-agentic-wealth-roadmap.md`** | **Strategic architecture and implementation roadmap for an AI-Agentic UHNW ETF Asset Management Platform** | Explanation / Reference |
| `spec.md` (v3.52) | Any question about *contract* — API shape, errors (§6.5), security model (§6.4), idempotency (§6.3), observability and health (§6.6), the pipeline and what actually gates (§12.1), the two run modes (§1), module boundaries (§3/§4) | Explanation |
| `architecture.md` | You need the shape of the system before the detail | Explanation |
| `api/openapi.yaml` | Changing a request/response, a status code, or a validation constraint. **The generated server interfaces come from here** — edit the contract, not the generated code. **Every parameter and property carries an example describing one coherent account, so this file *is* the Postman collection** — import it and you get 65 example responses already filled with that account. **For the request panes to be filled too, set *Parameter generation: Example*** in the import dialog: on the default, *Schema*, Postman fakes request values from types and ignores every example here, and no edit to this file can change that (measured — five different placements, none honoured). Import as *OpenAPI 3.1 Specification with a Postman Collection* so a contract change regenerates instead of importing a duplicate. `servers` and the `oauth2` flow mean **no environment is needed**: `baseUrl` resolves to `https://app.localhost` and *Get New Access Token* points at the realm's token endpoint — one click, no pasted JWT. Postman must trust `docker/tls/ca.crt` first, or every send fails on the dev CA. Do not hand-write a collection beside this | Reference |
| `adr/0001-kafka-delivery-path.md` | Touching event publication, the outbox, Kafka, or the transaction boundary around publishing | Explanation |
| `adr/0002-postgres-event-store.md` | Asking why Postgres is the system of record and Kafka only the bus, or why the topic and partition key are what they are | Explanation |
| `adr/0003-test-topology-and-ci-parallelisation.md` | Adding a `@SpringBootTest`, changing CI, or wondering why there is one integration context | Explanation |
| `adr/0004-readiness-does-not-gate-on-lag.md` | Touching the health probes, the readiness group, or the lag gauge — or asking why `E9` was rewritten rather than implemented as specified | Explanation |
| `adr/0005-kubernetes-is-the-production-target.md` | Adding a meter tag, a resource attribute or anything an operator consumes — or asking where this deploys, why Compose is not it, and why there are no manifests | Explanation |
| `agentic-workflow.md` | Understanding how this was built — including §5, where the agents were wrong, and §7, the per-phase gate record | Explanation |

The Diátaxis quadrants above (spec §8.1) are the ones that have documents. `docs/` still has no
`how-to/` or `tutorial/` tree — both directories held a `.gitkeep` and nothing else, and were removed
rather than left as a promise. The quadrant is not empty any more, though: the README is the
tutorial, and `docker.md` and `ledger-cli.md` are the two operational runbooks, kept as flat files
rather than reinstating a tree for two documents.

## Keeping this honest

A stale index is worse than none — it sends readers confidently to the wrong place. This file was itself
stale until 2026-08-05, listing five documents while `docs/` held four subdirectories and seven plans.

When you add a document, **add a row here by hand.** Nothing will remind you — **no gate enforces
anything about documentation in this repository.** There was one: a CI stage 6 wrapping a vendored
ISO governance test, which resolved its repository root inside its own skill directory and so
reported `17 known, 0 new` whatever changed under `docs/`. It was deleted on 2026-08-06 rather than
repaired, because repairing it would have made CI demand seventeen ISO compliance artefacts this
project has no business carrying. Spec §8.4 records the decision.

So this table is hand-maintained, and that is the whole of the mechanism. If a document's claims stop
matching the code, fix or retract them the same day — `spec.md` reached v3.8 for exactly that reason
(finding CR14).
