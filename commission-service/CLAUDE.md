# CLAUDE.md — commission-service

The write side. Prices closings, tracks cap progress, distributes revenue share, and records
domain events to a transactional outbox. Owns the Postgres schema.

Spring Boot 3.5, JPA/Hibernate 6, Liquibase, Postgres. Depends on `domain-core`; implements
its driven ports.

## Layout

```
service/                   RecordClosedTransactionService, BeneficiaryStandingResolverImpl
                           (+ their interfaces)
adapter/in/web/            TransactionController, the request/response shapes, ApiExceptionHandler
adapter/out/persistence/   entities, Spring Data repositories, port adapters, the mapper
adapter/out/messaging/     OutboxEventPublisher
config/                    domain beans, event and web serialization
resources/db/changelog/    Liquibase changesets
```

**Package convention.** `@Service` classes live in `service`, full stop — including
`BeneficiaryStandingResolverImpl`, which is a collaborator rather than a use case but is still a
stateless transactional service. Adapters stay `@Component` under `adapter/out/**`, because
they implement driven ports rather than orchestrating anything.

**Every `@Service` implements an interface** — `RecordClosedTransactionService` implements the
`RecordClosedTransaction` driving port, `BeneficiaryStandingResolverImpl` implements
`BeneficiaryStandingResolver`. Interface plus `Impl`, matching the convention in the other repos.
The reason is the seam and the stated contract, **not** the proxy strategy.

**Services are CGLIB-proxied, deliberately.** `spring.aop.proxy-target-class` is left at Spring
Boot's default of `true`, so an interface does not by itself produce a JDK dynamic proxy. Don't
"fix" this — it was tried and reverted. JDK proxies fail with a `ClassCastException` anywhere
something injects or casts to the concrete type, which is precisely why Boot made CGLIB the
default in 2.0. Spring repackages CGLIB and Objenesis inside `spring-core` (229 and 54 classes
respectively), so the old objections — extra dependency, jar conflicts, required default
constructor — no longer apply.

The one real cost of CGLIB is that `final` classes and methods are silently unadvised. Avoid it
by not making service classes or methods final, rather than by overriding the framework default.

Dependencies point inward. Nothing here is imported by `domain-core`; this module implements
interfaces declared there.

## Four Postgres traps this module already hit

All four cost real debugging time. Do not undo the fixes.

**1. A constraint violation aborts the whole transaction.** Postgres marks the transaction
failed, and every subsequent statement returns `current transaction is aborted, commands
ignored until end of transaction block`. So the tempting pattern — insert, catch the
duplicate-key error, read back the row the winner inserted — *cannot work*: the recovery read
is itself a subsequent statement. Both racy inserts here use native
`INSERT … ON CONFLICT DO NOTHING` and check the affected row count instead
(`CapProgressJpaRepository.insertIfAbsent`, `CommissionSplitJpaRepository.insertIfAbsent`).
**Never catch a `DataIntegrityViolationException` and then query in the same transaction.**

**2. `save()` on an assigned id silently updates.** Spring Data decides insert-versus-update
by asking whether the id is null. Every id here is client-assigned and therefore never null,
so `save()` takes the `merge()` path: SELECT, then UPDATE if the row exists. For
`commission_split` that meant a replayed event would *overwrite the original pricing* rather
than being rejected — the opposite of an append-only record. Hence the native upsert.

**3. The same trap costs a wasted SELECT per row.** `RevenueShareAwardEntity` and
`OutboxEntity` implement `Persistable` with a `@Transient isNew` flag so Spring Data skips the
pre-insert SELECT. One closing writes up to five awards and three events; without this that is
eight pointless round trips on the hot path.

**4. `spring-boot:repackage` breaks Failsafe.** It is bound to `package`, which runs *before*
`failsafe:integration-test`. Without a classifier it rewrites the module jar in place into a
fat jar with classes under `BOOT-INF/classes`, Failsafe puts that on the test classpath, and
the run dies with `TestEngine with ID 'junit-jupiter' failed to discover tests` and no cause
anywhere. The `<classifier>exec</classifier>` in this POM is what keeps the plain jar primary.
(A related trap lives in the root POM: `junit-platform-launcher` must be declared explicitly,
or Surefire resolves a JUnit 6 launcher against the JUnit 5 engine and reports the same
useless message.)

## Concurrency

`cap_progress` is the only read-modify-write aggregate, and the only place a lost update costs
money. Two closings priced in parallel for one agent would both read the same balance, both
compute a full 15% share, and both write — leaving the agent under-contributed and free to
earn past their cap, with nothing in the data to show it.

`@Version` on `CapProgressEntity` turns that into a retryable
`ConcurrentCapUpdateException`. `CapProgressConcurrencyIT` proves it with real threads, and
asserts the retry count is **positive** — if that ever hits zero the threads stopped racing
and the test stopped testing anything.

Optimistic, not pessimistic: contention only happens when one agent closes two deals at the
same instant, so a row lock on every closing would cost more than it saves.

## Transaction boundary

`RecordClosedTransactionService.record` is one transaction covering the cap advance, the
split, the ledger, and the outbox writes. Keep it that way — the outbox only solves the dual
write if it commits with the state change it describes. `OutboxEventPublisher` is
`Propagation.MANDATORY` so calling it outside a transaction fails loudly.

## Idempotency

Two layers, and both are needed:

1. `splits.exists()` on the way in — cheap, catches the common redelivery.
2. The `ON CONFLICT` row count — catches two deliveries racing each other, which both pass
   the first check.

Awards inherit idempotency structurally: they are only written after the split insert
succeeded, so a duplicate distribution cannot reach the ledger.
`uq_award_transaction_beneficiary` stays as a database backstop and is deliberately *not*
caught (see trap 1).

## The API

`POST /transactions` is the whole surface: one endpoint for the one use case. `TransactionController`
parses, calls the `RecordClosedTransaction` port, and maps the outcome to a status. It does no
arithmetic and opens no transaction of its own.

**201 for a new closing, 200 for a replay.** Idempotency is the port's contract, so it belongs in
the status line — a client retrying after a timeout can tell "I recorded this" from "this was
already recorded" without parsing the body. No `Location` header: this service exposes no read of a
transaction, and pointing at the reporting service's projection would promise a resource that 404s
until the event lands.

**The retry loop lives in the controller, and cannot live further in.** `CapProgressRepository`
says a conflict is the caller's to resolve by re-reading and retrying. The reason it is the
caller's is that the whole recording is one transaction — retrying inside it would reuse a
transaction Postgres has already marked failed (trap 1), so the attempt has to be a *new* one and
the loop has to sit outside the `@Transactional` boundary. The controller is the first place that
is true. Bounded at five attempts with jittered backoff; exhaustion is a `409` marked `retryable`,
never an unbounded wait holding a connection from a deliberately small pool.

**Validation is split by who owns the rule.** `RecordClosingRequest` annotates presence only — a
missing field is a fact about the request, and an NPE from a domain constructor would be a 500 for
what is plainly a 400. Every *business* invariant (positive amounts, commission not exceeding sale
price) stays in `ClosedTransaction`, and `toClosedTransaction()` translates its
`IllegalArgumentException` into a 400. Do not re-state those rules as annotations: that gives one
rule two homes that drift, and it is the same "adapters translate, they do not decide" line the
persistence adapters hold.

**`ClosingReceiptView` omits the revenue share block on a replay** rather than reporting zeros.
`replayOf` returns `RevenueShareDistribution.none(...)` — it deliberately does not re-read the
ledger — so rendering it would assert that the closing paid nobody, when the truth is that the
response does not know. Same principle as the read side answering 404 instead of an empty dashboard.

## Event payloads are a published contract

`EventSerializationConfiguration` defines a dedicated `eventObjectMapper`, injected by
`@Qualifier`. Do not switch it to the auto-configured application `ObjectMapper`: a property
set for an HTTP response would then silently change the format of every event, with old and
new shapes interleaved in one table and nothing recording which is which.

`AgentId` and `TransactionId` serialise as bare strings, `Money` as a JSON number.

**Adding the web starter armed exactly the trap that comment predicted.** Boot's `ObjectMapper` is
`@Primary` *and* `@ConditionalOnMissingBean`, so because `eventObjectMapper` already existed, Boot
backed off entirely — leaving **no primary mapper at all** and the event mapper as the only
candidate for serialising every HTTP response. Nothing looks wrong on the day; it is the next
API-motivated Jackson tweak that silently rewrites the event contract. `WebSerializationConfiguration`
declares the web mapper explicitly as `@Primary`, and `SerializationBoundaryIT` is the guard — watched
failing with that bean removed, which is the only way a wiring test proves anything. The read side hit
the identical trap; do not "simplify" either module down to one mapper.

## Conventions

- **Adapters translate, they do not decide.** Business rules belong in `domain-core`. If an
  adapter is branching on domain state, the rule is in the wrong place.
- **Never annotate a domain type.** Entities are separate by design; `PersistenceMapper` is
  the only place the two shapes meet.
- Prefer a derived Spring Data query; drop to `@Query` when the JPQL is clearer, and to
  `nativeQuery` only where Postgres semantics are the point (the upserts).
- Integration tests are `*IT` (Failsafe, needs Docker), unit tests `*Test` (Surefire). Extend
  `AbstractPostgresIT`, which starts one container for the whole suite.
- **`TransactionControllerTest` stubs the port by hand and is not a third mock.** The controller
  takes `RecordClosedTransaction` as a constructor argument, so "fail twice, then succeed" is a
  small class rather than a mocking framework — which is the check the root `CLAUDE.md` asks for.
  It exists only for what a real database cannot be asked to do on cue: lose the optimistic lock
  five times running. Everything reachable without staging a failure belongs in `TransactionApiIT`.
- Every index in the schema has a comment naming the query it serves. Add new ones the same
  way, or they become unremovable.
