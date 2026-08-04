# CLAUDE.md — reporting-service

The read side. Consumes the domain event stream and maintains a denormalized agent dashboard
in MongoDB: cap progress, production, revenue share earnings, and the tier-grouped downline.
Owns the Mongo read model.

Spring Boot 3.5, Spring Data MongoDB, Spring Kafka. Depends on `domain-core` for the event
types only.

## Layout

```
service/                   DashboardProjector (+ Impl) — the fold over the stream
                           AgentDashboardQuery (+ Impl), AgentDashboardView — the read
adapter/in/messaging/      DomainEventConsumer, DomainEventReader, EventTopics
adapter/in/web/            AgentDashboardController, ApiExceptionHandler
adapter/out/mongo/         repositories and the documents they store
config/                    event deserialization, web serialization, Mongo transactions, clock
```

Conventions follow `commission-service`: every `@Service` implements an interface, adapters
are `@Component` under `adapter/**`, integration tests are `*IT` (Failsafe) and unit tests
`*Test` (Surefire).

## This module holds no business rules

Every figure on a dashboard was calculated by the write side's pure calculators and carried in
an event. The projector sums and groups; it never decides. **If something here starts branching
on domain state, the rule is in the wrong module** — the same rule the write side's adapters
follow.

The one thing that looks like a rule and is not: clearing the capped flag when the cap year
rolls over. That is not a decision about capping, it is the projection noticing that the event
it just received belongs to a different window than the document it is updating.

## Mongo needs a replica set, and that is load-bearing

`DashboardProjectorImpl.apply` marks an event processed and folds it into up to five dashboards
in **one transaction**. Both orderings without a transaction lose:

- mark then apply — a crash between leaves the event marked and never projected; nothing
  retries it and the dashboard is permanently short;
- apply then mark — a crash between replays the event and counts it twice.

Multi-document transactions need a replica set. `docker-compose.yml` runs `mongod --replSet rs0`
and initiates it from the healthcheck; `MongoDBContainer` does the equivalent by itself.

**`MongoTransactionManager` is not auto-configured.** Spring Boot will not create one, and
without it `@Transactional` on a Mongo call is *silently* a no-op — the projector looks correct
and double-counts on redelivery. `MongoTransactionConfiguration` declares the bean and
`ProjectionAtomicityIT` is the test that fails if it goes away. Do not delete either.

## The downline is two populations, not one

Each tier carries both, and neither substitutes for the other:

- **`contributors`** — agents at that depth who have earned this agent something. Built from
  `RevenueShareDistributed`, which is where the money is.
- **`downline`** — every agent sponsored at that depth, earning or not, departed or not. Built from
  `AgentEnrolled`, which is the only event that knows about an agent before they produce.

The gap between the counts is the interesting figure. Thirty in the downline and two contributors
describes a recruiting record with no income behind it, and collapsing them into one number loses
exactly that.

**`contributors` stays a subset by construction.** `TierView.add` registers the contributor in the
roster too, because an award *is* proof of membership at that tier. Without it, every agent enrolled
before the write side emitted `AgentEnrolled` would give their ancestors more contributors than
downline. The join date stays null there — the award carries none, and guessing is worse than
admitting it is unknown.

**A departure marks, it never removes.** The hierarchy does not compress, so an agent who leaves
keeps their depth forever and everyone beneath them keeps their tier. Deleting the entry would
assert a tree shape the write side does not have, and would contradict awards still arriving through
them from further down.

**There is deliberately no live "producing frontline" count**, and this is the one place the
temptation is real, because the tier-unlock thresholds are defined on exactly that number.
Producing means $450 of gross in the trailing six months — a policy evaluated against a clock, which
is a decision, which this module does not make. `contributorCount` is *not* that number either: it
is lifetime earning, so an agent who produced two years ago and stopped still counts. The view
serves `requiredToUnlock` from `RevenueShareTier` as plan data and stops there.

## Fan-out: two events touch other agents' dashboards

`RevenueShareDistributed` writes up to five beneficiary dashboards; `AgentEnrolled` and
`AgentTerminated` write up to five ancestor dashboards plus the agent's own. Both walk the frozen
sponsorship path by index — index 0 is tier 1 — and `forEachAncestorInReach` is the single place
that conversion happens, which is why neither projection carries an off-by-one.

The walk ends where `RevenueShareTier.atDepth` returns empty. Ancestors beyond the fifth are real
and are in the path, but the dashboard is organised by the programme's five tiers and has no row for
them.

## Idempotency

Delivery is at-least-once, and most of this projection is additive, so redelivery must be
recognised. `processed_event` holds one document per applied event id; the projector checks it
before folding and writes it in the same transaction.

Two things are deliberately *not* deduplicated by transaction id:

- distinct events for the same closing (a commission event and its cap announcement) are
  separate facts and both must land;
- the check keys on `eventId`, never on the content of the event.

The TTL on `processed_event` must stay **longer than the topic retention**. An event Kafka can
no longer redeliver cannot be a duplicate, so 30 days against 7 days of retention is the
argument. Shortening it below retention is the subtle way to reintroduce double-counting.

## Absolute versus accumulated fields

Worth knowing before adding a field to the dashboard:

- **Absolute** — cap progress. `CommissionCalculated` carries the post-state, so the projection
  overwrites. Safe to replay, and per-agent partitioning means the last write really is latest.
- **Accumulated** — production totals, revenue share earnings, downline membership. These add,
  and are only correct because of the idempotency above. Roster membership accumulates by *key*
  rather than by sum, which is what makes a redelivered `AgentEnrolled` harmless.

Getting this backwards is the failure that looks right in a demo: summing the cap balance makes
an agent appear to cap on their second closing.

## The payload contract

`EventDeserializationConfiguration` mirrors `EventSerializationConfiguration` on the write side —
`AgentId` and `TransactionId` as bare strings, `Money` as a JSON number. Neither can be
reconstructed by reflection, so the two files have to change together.

- Unknown **fields** are tolerated (`FAIL_ON_UNKNOWN_PROPERTIES` off), so the write side can add
  one and deploy first. Missing fields still fail — a field that vanished is a real break.
- Unknown **event types** are logged and skipped, not thrown. A type this service has never heard
  of cannot affect its projections, and throwing would be a crash loop on a record no redeploy
  gets past.
- A **known** type that will not parse does throw. That is a genuine contract break, and
  projecting around the gap would make the dashboard quietly wrong rather than visibly stuck.

`DomainEventReaderTest` pins the format against **literal JSON**, on purpose. Round-tripping
through a matched writer and reader only proves the two agree with each other, and passes just
as happily after both have drifted.

## Events are deserialized into `domain-core` types

Not local DTOs. Three consequences worth keeping:

1. The dispatch in the projector is an exhaustive `switch` over the sealed `DomainEvent`
   hierarchy, so a sixth event type is a compile error here rather than an event that silently
   projects to nothing.
2. The domain records' validating constructors run on the way in. A split that does not balance
   is rejected at the boundary.
3. This module must never import a calculator, a port, or anything from `commission-service`.
   The dependency is on the event vocabulary, nothing else.

## Two ObjectMapper beans, and the one that must stay @Primary

Adding the web starter armed a trap the write side's `EventSerializationConfiguration` had
predicted in words. Boot's own `ObjectMapper` is `@Primary` **and** `@ConditionalOnMissingBean`.
Because `eventObjectMapper` already exists, Boot backs off — and the result is not "two mappers,
each used correctly", it is **no primary mapper at all**, leaving the event mapper as the only
candidate and therefore the one Spring MVC serialises every HTTP response with.

`WebSerializationConfiguration` declares the web mapper explicitly and marks it `@Primary`.
`SerializationBoundaryIT` is the guard, watched failing with the bean removed. **Do not delete
either, and do not tune `eventObjectMapper` to fix an API problem** — the payload format is a
contract with events already durable in the outbox.

## The read path is read-only, structurally

`AgentDashboardController` exposes `GET` and nothing else. State changes by publishing a closing
to `commission-service`; a mutating endpoint here would be a second way into the system with no
cap arithmetic behind it, and the two models would immediately disagree. There is a test
asserting `POST` is rejected.

404 for an unknown agent is deliberate over an empty 200: returning an empty dashboard would
assert zero production as fact when the truth is that nothing is known.

`AgentEnrolled` narrowed what that 404 means. An enrolled agent now gets a dashboard on the day
they join, reading zero — and that zero is a fact. Only an agent nobody ever enrolled is absent.
The `affiliation` block is null for agents this service learned about from a closing or an award
instead, which is every agent who predates the lifecycle events; present-and-empty is not the
same as absent, so it is served as null rather than a zeroed record.

## Testing

- `DashboardProjectionIT` — the projection rules, driven straight against Mongo. No broker; Kafka
  delivers events but decides nothing about what they mean.
- `DownlineRosterProjectionIT` — the org chart half: enrolment fan-out, the five-tier reach
  limit, departures marked rather than removed, and contributors staying a subset of the
  downline even with no enrolment event.
- `EventStreamIT` — the delivery path once, end to end. Header dispatch, deserialization, the
  listener.
- `ProjectionAtomicityIT` — the transaction, with a mock, because a mid-transaction
  infrastructure failure is not something a real Mongo can be asked for on cue.
- `DomainEventReaderTest` — the wire format, as literal JSON.
- `AgentDashboardControllerTest` — the HTTP contract as a `@WebMvcTest` slice with the query
  stubbed. The second of the repository's two mocks: routing, status codes and JSON shape are
  genuinely all that is under test, and the not-found case is a state of the query result rather
  than of the database.
- `AgentDashboardApiIT` — events in, JSON out, joining the two ends once.
- `SerializationBoundaryIT` — the two mappers stay separate.

`MongoDBContainer` starts once per suite and documents are cleared between tests. Clear the
*documents*, not the collections: dropping a collection takes its TTL index with it.
