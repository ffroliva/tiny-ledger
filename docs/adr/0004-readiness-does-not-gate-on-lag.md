# ADR 0004 — Readiness does not gate on lag

**Status:** Accepted
**Date:** 2026-08-07
**Context:** spec §6.6 (Health), §9.3 case `E9`, §4.3; supersedes the health paragraph as written
through spec v3.31

## Context

Spec §6.6 said, from v3.0 through v3.31:

> Readiness gates on event-store reachability **and projection lag under threshold**, so an instance
> whose read models have fallen behind stops taking traffic instead of serving stale balances.

Case `E9` was written directly against that sentence: *"Hold the listener until projection lag exceeds
the threshold → the readiness probe reports not ready; the instance stops receiving traffic rather than
serving stale balances."* `E9` is the last open case in the §9.3 catalogue, and it stayed open through
a dozen revisions described first as *deferred by decision*, then — correctly, at v3.26 — as *unbuildable
because the feature is absent*.

Both descriptions were right about the outcome and wrong about the cause. The feature was indeed absent.
But building it would not have made `E9` pass, because **the behaviour `E9` describes cannot occur in
this architecture**.

## The finding

**Balance-projection lag is structurally zero, not merely small.**

`LedgerEventsListener` is a plain `@EventListener`. It runs synchronously, on the publishing thread,
inside the write transaction, in **both** run modes — ratified at spec v3.5 (§4.3, "Standalone caveat")
and unchanged since. The listener's own javadoc records why `@ApplicationModuleListener` was rejected,
and §6.6's trace-context table states the same fact four paragraphs above the claim it contradicts:

> Two of those arrows cross a thread or process boundary — *the projection does not, it runs on the
> publishing thread inside the same transaction (§4.3)* — and on each one an unconfigured setup silently
> starts a fresh trace.

So there is no window in which a balance is stale. A read either sees the write or the write has not
committed. `E9`'s stated harm has no mechanism, and `E9`'s stated method — holding the listener — would
block the write itself rather than produce lag behind it.

**The lag that does exist is somewhere else.** The Kafka leg is the only asynchronous path: events reach
the broker through the Modulith event-publication registry (`event_publication`, migration `004`), and
`AuditKafkaListener` consumes them in the `audit` module. When Kafka is slow or down, incomplete
publications accumulate and the audit consumer falls behind.

That lag makes the **audit trail** stale. It does not make balances stale.

## Decision

**Readiness gates on event-store reachability only. Lag is measured and published as a gauge, and
nothing gates on it.**

| Probe | Contains | Reason |
|---|---|---|
| `liveness` | `livenessState` | A liveness probe that fails on a dependency restarts a process that was working |
| `readiness` | `readinessState` + `db` | In `full` the event store **is** Postgres. In `standalone` it is in-memory, so the group is `readinessState` alone |
| — | `redis`, `kafka` **excluded** | See below |

`ledger.audit.lag.seconds` is a gauge over the age of the oldest incomplete publication. It exists in
`full` only, because `standalone` has no `event_publication` table. The spec's existing numbers survive
as **alerting** thresholds, relabelled to describe audit-trail lag rather than projection lag: p99 < 2 s
steady-state, 5 s worth paging on.

## Why gating on the real lag would be worse than not gating

Because it would fail a case this suite already passes.

**E11** requires that with Kafka paused, writes still return `201`, `?consistency=strong` still returns
the correct balance, and the write does not block on the broker. It was measured on 2026-08-07 at 164 ms
— indistinguishable from a healthy write — which is the evidence behind ADR 0002's "Kafka is the courier,
Postgres is the record".

An instance that flipped `readiness` to DOWN when outbox lag crossed five seconds would remove *every*
instance from service during exactly that outage. The ledger would stop serving reads and writes it is
provably capable of serving correctly, because a downstream compliance consumer was behind. That inverts
the property ADR 0002 was written to protect.

The same argument excludes Redis and Kafka from the readiness group. Spring Boot auto-configures a health
indicator for each, and the default grouping would include them — so this is a decision that has to be
*made*, not one that can be left alone. **E10** (Redis paused: rate limiting fails open, the write still
`201`s, strong reads stay exact) and **E11** would both break against a readiness group that trusted
Boot's defaults.

## Consequences

- **`E9` is rewritten, not closed.** It now asserts the honest behaviour: pause the audit consumer, the
  gauge rises past the threshold, balances stay exact, readiness stays **UP**. It remains the one open
  case in §9.3 and closes when §14 step 9 lands.
- **The 2 s / 5 s thresholds have no enforcement.** They are inputs to an alert this repository does not
  ship. Per `AGENTS.md` — *if you state a rule that this file does not enforce, say which gate enforces
  it, or say plainly that none does* — §6.6 now says plainly that none does.
- **The how-to quadrant stays empty.** Spec §8.1 wants a runbook answering *"lag is alerting, what do I
  do?"*. Step 9 gives the gauge a value but nothing pages on it, so there is still no trigger to write a
  procedure for, and §8.1 continues to name the absence rather than fill it.
- **This does not close the door on gating.** If the projection is ever moved off-thread — the listener's
  javadoc calls that "a Plan 3 question" — projection lag becomes real and this ADR should be revisited.
  The decision is about the architecture as built, not about the idea.

## Alternatives rejected

**Implement §6.6 literally.** Gate readiness on outbox lag at the 5 s threshold. Faithful to the spec and
to `E9` as written, and needs no spec change. Rejected: it contradicts `E11`, and `KafkaOutageIT` would
have to be changed to accommodate it — rewriting a passing test to match a sentence, rather than the
reverse.

**Move the projection off-thread so lag is real.** Make `LedgerEventsListener` asynchronous behind a flag,
then gate on the lag that results. The only option under which `E9` is literally true. Rejected: it
reverses a decision ratified at v3.5, breaks read-your-writes, makes the two run modes diverge in a way
§9.2b treats as a defect, and is far outside §14 step 9's scope. Producing a failure mode in order to
build a guard against it is not an improvement.
