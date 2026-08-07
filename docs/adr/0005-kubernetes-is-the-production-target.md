# ADR 0005 — Kubernetes is the production target; Compose is local only

**Status:** Accepted
**Date:** 2026-08-07
**Context:** spec §1 (run modes), §6.6 (observability), §12 (Docker and delivery); constrains
`adr/0004-readiness-does-not-gate-on-lag.md`

## Context

Through spec v3.33 this repository had exactly one deployment story: `docker compose up`, then run the
jar on the host. That is honest about what exists, and it is the right shape for a local run and for
the integration suite. It is not a production topology, and nothing in the documents said what the
production topology would be.

That silence had a cost that was about to be paid. §14 step 9 adds observability, and observability is
the subsystem whose decisions are least reversible: metric names, tag sets and resource attributes get
written into dashboards, alerts and saved queries by people who are not reading this repository. Adding
them shaped for a single hand-run process, then reshaping them for a cluster, means changing every
consumer downstream of the change.

The stated principle behind this ADR: **a ledger that is not scalable is not a real ledger.** An
event-sourced banking system whose story ends at one process on one laptop has not answered the
question its design exists to answer.

## Decision

**Kubernetes is the production runtime. Terraform is the mechanism that produces it. Compose is for
local development and the test suite, and is not a deployment artefact.**

Three consequences that bind the code, rather than remaining aspiration:

1. **Cloud portability runs through OTLP, not through an abstraction layer.** The application speaks
   OTLP to one endpoint and knows nothing else. In Compose that endpoint is a Collector service; in a
   cluster it is a DaemonSet or gateway Collector; the destination behind it — Grafana Cloud, Azure
   Monitor, AWS, GCP — is Collector configuration. This is the design §6.6 already had, and it is
   recorded here as *load-bearing* rather than incidental: it is why the Compose-era choice of Grafana
   Cloud does not become a lock-in.
2. **Determinism is a requirement, not a preference.** Images are pinned by digest, never by a moving
   tag. `latest` is the opposite of reproducible, and an observability stack that silently changes
   version is one whose dashboards silently change meaning.
3. **The scalability constraints below are rules with named enforcement, or they are named as
   unenforced.** Per `AGENTS.md`: a rule with no gate is a hope, and the difference must be visible.

## The constraints this places on step 9

These are the decisions that are cheap now and expensive to retrofit. Each is here because the
single-process version of the choice is *different*, not merely smaller.

### Metric cardinality is a one-way door

**Account identifiers, movement UIDs and interaction ids go on spans and logs. They never go on
meters.**

§6.6 lists `ledger.account_id` and `ledger.stream_version` as domain attributes. On a span that is
correct and it is most of the value — a span is a sampled individual record and high-cardinality
attributes are what make it worth keeping. A **meter** is a time series per unique tag combination.
Tagging a counter with an account id creates one series per account, forever, and at scale that does
not degrade a metrics backend, it takes it down.

Meter tags stay bounded and enumerable: movement type, rejection reason, endpoint, status class,
outcome. If a proposed tag's value set grows with traffic, it belongs on the span.

**Enforcement: none today.** It is a review rule, and it is stated here because it is the kind of
mistake that looks harmless in a diff and is discovered in production.

### Resource attributes decide whether replicas are debuggable

Every signal carries `service.name`, `service.namespace` and `service.instance.id`, sourced from the
environment rather than hardcoded, plus the `k8s.*` conventions where the platform supplies them.

Without them, twenty replicas produce one indistinguishable stream and *"which instance is slow"* has
no answer. This is the retrofit that hurts most, because the fix is invisible in the application and
visible in every dashboard, alert and saved query built before it.

### Readiness earns its second job under an orchestrator

ADR 0004 decided readiness gates on event-store reachability and **not** on lag. That decision stands
unchanged. What changes is that readiness acquires a role it did not have when the app was started by
hand: **on SIGTERM the pod must leave the Service before the listener stops.**

`server.shutdown=graceful` plus Boot's readiness flip on shutdown is what makes a rolling deploy or a
scale-down safe. Without it, in-flight writes die mid-request during an ordinary deployment. For a
ledger that is a correctness property, not an operational nicety — and it is the reason the readiness
probe matters even though nothing in ADR 0004's reasoning required it to.

### The lag gauge is global and must aggregate as `max`

`ledger.outbox.pending.age.seconds` reads a shared table, so every replica reports the *same* value. Summed
across twenty pods it reads twenty times the truth. It aggregates with `max`, and dashboards and alerts
must say so.

This is worth stating because the wrong aggregation is not obviously wrong on a chart — it is a
plausible number that is simply false, which is the hardest class of monitoring defect to notice.

### Management endpoints move to their own port

Probes bind to `management.server.port`, unpublished, reachable from inside the network only. Under an
orchestrator this is standard, and it changes the failure mode of a misconfiguration from *exposed but
denied* to *not reachable at all*.

ADR 0004's first version deferred this, with the trigger "revisit at deployment to an orchestrator".
That trigger fired the moment Kubernetes became the stated target, so it is built now rather than
carried as debt. It costs one documented Spring context fork in the probe tests, since MockMvc cannot
reach a second port — `AGENTS.md` trap 5 requires that fork to have a written reason, and this is it.

## Consequences

- **Nothing about the Collector-to-Grafana-Cloud choice changes.** It was already the swap point, which
  is the property being relied on. Recorded so a future reader does not re-open a settled decision.
- **`.env.example` and §1.5 grow the resource-attribute variables.** Config stays environment-only,
  which is what a cluster wants anyway.
- **No manifests and no Terraform are written by this ADR.** Deliberately. This repository's own history
  is that unbuilt specification rots into claims — §14's struck steps and the deleted CI governance gate
  are both that lesson. What is recorded here are the decisions that *shape code being written now*. The
  cluster and its infrastructure-as-code are a separate future plan, and until they exist §12 continues
  to say plainly that Compose is what there is.
- **One scaling question is opened rather than answered** — see below.

## Opened, not answered: multi-replica event-publication resubmission

**This is a backlog item, not a finding, and not a blocker for anything currently being built.** The
Kubernetes stack is the direction, not the primary target today; the ledger runs as a single process
and the question below has no way to bite until a second replica exists. It is recorded so that it is
tracked and re-derived rather than discovered.

**No conclusion is drawn here.** What follows is the two facts that are measured, and the work that
would settle the rest.

**Measured, 2026-08-07:**

- `application-full.properties:56` sets
  `spring.modulith.events.republish-outstanding-events-on-restart=true`. The resubmission mechanism is
  enabled in this application — that much is not hypothetical.
- `003-init-audit-store.sql:15` declares `UNIQUE (account_id, stream_version)` on `audit_entries`. A
  duplicate ledger event reaching the audit trail would meet a constraint rather than silently
  duplicating a row.

**Not established, and not to be assumed either way:**

1. Whether two instances starting against the same `event_publication` table can each resubmit the
   *same* incomplete row — i.e. whether Modulith takes any lock or lease on the row, or whether
   resubmission is unconditioned. Nobody here has read the mechanism.
2. What the audit consumer does when a duplicate arrives. The unique index means the insert fails; what
   is unknown is whether that failure is absorbed as an expected idempotent replay, logged and dropped,
   or surfaced as a consumer error that retries the same record indefinitely. The third would be worse
   than the duplicate.
3. Whether §6.3's idempotency covers this path at all, or only the client-facing `movementUid` path.

**The use case that closes the gap.** *As an operator running more than one replica, I need a Kafka
outage followed by recovery to leave the audit trail exactly consistent with the ledger, so that the
compliance record is neither short nor duplicated after an ordinary infrastructure incident.*

**How it gets settled — research, then reproduction, then a test. In that order, and no step skipped:**

1. **Research.** Read `spring-modulith-events-core` and `-jdbc` at the version this project pins (2.1.0):
   how resubmission is triggered, and whether the registry conditions the read on anything. Check
   Modulith's own documentation and issue tracker for multi-instance guidance — this is a common enough
   deployment that an answer may already exist and be citable, which would be cheaper and more reliable
   than inferring it from source.
2. **Reproduce.** Two application instances against one Postgres and one Kafka. Pause Kafka, write, let
   publications go incomplete, restart both instances, unpause. Count the messages on `ledger.events`
   for the movement UID. This is the step that turns a reading of the source into an observed fact —
   and per `AGENTS.md` trap 7, run it against a control that must produce exactly one message, so a
   count of one proves the harness works rather than proving nothing.
3. **Characterise the downstream.** Whatever the count, drive a known duplicate through
   `AuditKafkaListener` and observe what the unique index does to it. That behaviour matters even if
   resubmission turns out to be safe, because a duplicate can arrive from a Kafka redelivery regardless.
4. **Then decide.** If there is no duplicate, record why with the citation and close it. If there is,
   it is a defect with a reproduction, and it gets a fix and a test like any other.

**Tracked as [issue #9](https://github.com/ffroliva/tiny-ledger/issues/9).**

**Acceptance:** a documented answer with evidence attached — either a cited mechanism plus a passing
reproduction that shows exactly one message, or a defect with a red test. *"It is probably fine because
idempotency"* does not close it.

## Alternatives rejected

**Stay Compose-only and decide later.** Cheapest today. Rejected because the observability decisions
being made *this week* — meter tags and resource attributes above all — are consumed by dashboards and
alerts outside this repository, and are the ones that cannot be quietly corrected afterwards.

**Write the full Kubernetes and Terraform specification now.** Most complete. Rejected on this
repository's own evidence: a specification for infrastructure nobody has built becomes a claim that
reads as delivered. §14 step 13 was struck and CI stage 6 was deleted for precisely that failure. The
decisions that constrain code are recorded; the infrastructure is not described until it exists.

**A cloud-provider abstraction layer in the application.** Rejected as the thing OTLP already is. An
interface with one implementation per cloud is the abstraction the OTel Collector was built to make
unnecessary.
