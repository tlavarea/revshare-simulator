# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A portfolio project: an event-driven, CQRS reference implementation of a real-estate
brokerage's agent compensation programme — an 85/15 commission split with a $12,000 annual
cap, a flat per-transaction fee after capping, and a five-tier revenue share programme paid
out of the company's share.

It exists to demonstrate DDD, hexagonal architecture, CQRS, event-driven microservices, and
testing depth. Optimise accordingly: **a reviewer reads this repo, they do not operate it.**
Code that is clever but unexplained is worth less here than code that is ordinary and
justified. Comments should say *why*, especially where a decision could look like an
oversight.

Modules:

- **`domain-core/`** — the framework-free hexagonal core. Zero compile-scoped dependencies,
  enforced by the build. See `domain-core/CLAUDE.md`.
- **`seed-generator/`** — deterministic synthetic data. See `seed-generator/CLAUDE.md`.
- **`commission-service/`** — the write side: Postgres, JPA, a transactional outbox relayed to
  Kafka by `OutboxRelay`, `POST /transactions`, and agent enrolment. See
  `commission-service/CLAUDE.md`.
- **`reporting-service/`** — the read side: Kafka consumer folding the event stream into MongoDB
  dashboards — earnings and the tier-grouped org chart — served by
  `GET /agents/{id}/dashboard`. See `reporting-service/CLAUDE.md`.

`README.md` is the reviewer-facing document and carries the full domain explanation, the
architecture diagram, and the table of documented assumptions. **When a business rule or an
assumption changes, update the README in the same commit** — it is the artefact the project
is actually judged on.

## Commands

**Requires JDK 21.** The default `JAVA_HOME` on this machine is JDK 26, which Spring Boot 3.5
cannot run on (its bundled ASM/CGLIB cannot read the class files). The root POM's enforcer
rule fails the build outside `[21,25)` with a message rather than letting it break somewhere
confusing.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

./mvnw verify                      # full build + all tests
./mvnw -pl domain-core test        # one module
./mvnw -Dtest=CapYearTest test     # one test class
./mvnw spotless:check              # what CI checks
./mvnw spotless:apply              # what the build does automatically at `compile`

docker compose up -d               # Postgres, MongoDB, Kafka (not needed for `verify`)

# Generate synthetic data / see what the domain rules make of it
./mvnw -q -pl seed-generator exec:java \
  -Dexec.mainClass=com.revshare.seed.SeedGeneratorCli \
  -Dexec.args="--summary-only"
```

## Conventions

**Work on branches, never commit directly to `main`.** Create or switch to a branch before
starting. `main` is protected.

**Spotless runs automatically after every edit** (a `PostToolUse` hook in
`.claude/settings.json`, plus an `apply` execution bound to Maven's `compile` phase). Two
consequences worth knowing:

- It **strips an import added before its usage exists**. If you add an import in one edit and
  the code that uses it in a second, the import is gone by the time you write the second.
  Always add an import and its first usage *in the same edit*.
- It reflows Javadoc to 120 columns and reorders imports. Do not hand-format around it; write
  the content and let it settle the layout.

**Java style** is palantir-java-format at 120 columns, matching the auth-lava backend.

**Prefer records for value objects and immutable results; use a class for an aggregate root
with behaviour.** That distinction is deliberate and load-bearing — see
`domain-core/CLAUDE.md`.

## Architecture

```
                        writes                          reads
                POST /agents, /transactions   GET /agents/{id}/dashboard
                             ▼                             ▲
 ┌──────────────┐   ┌──────────────────┐          ┌───────────────────┐
 │ seed-        │──▶│ commission-      │  Kafka   │ reporting-service │
 │ generator    │   │ service          │─────────▶│                   │
 └──────────────┘   │  Postgres        │  events  │  MongoDB          │
                    └──────────────────┘          └───────────────────┘
                             │                             │
                             └──────────┬──────────────────┘
                                        ▼
                                 ┌─────────────┐
                                 │ domain-core │  no framework, no I/O
                                 └─────────────┘
```

The dependency arrow points inward and only inward. `domain-core` declares driven ports in
`com.revshare.domain.port.out`; the service modules implement them. Nothing in the core may
import from a service module.

The root POM **imports the `spring-boot-dependencies` BOM** rather than inheriting from
`spring-boot-starter-parent`, specifically so the parent's plugin bindings and resource
filtering never reach the core. Do not "simplify" this by switching to parent inheritance.

### Consistency decisions already made, recorded in the ports

These are written into the port Javadoc where an implementer will see them. Honour them when
the service modules get built:

- **`CapProgressRepository`** — cap progress is read-modify-write. Concurrent closings for one
  agent must not both read the same balance. Needs optimistic locking or `SELECT … FOR UPDATE`
  with caller retry.
- **`DomainEventPublisher`** — writing Postgres then Kafka is a dual write. Use a transactional
  outbox; never publish outside the transaction that produced the event.
- **`RevenueShareLedger`** — append-only and idempotent on transaction id. At-least-once
  delivery must not double-pay an upline.
- **Partitioning** — commission events key on the *agent* (cap progress is cumulative and
  order-sensitive); revenue share events key on the *contributor* (one event concerns up to
  five beneficiaries, so no beneficiary can own the ordering); agent lifecycle events key on
  the *agent themselves*, so an enrolment is never consumed after the termination that
  followed it.

## Testing

- **JUnit 5 + AssertJ. No mocking framework in `domain-core`** — both calculators are pure
  functions and do not need one. If you find yourself reaching for a mock there, the
  dependency probably belongs in an argument instead. See `BeneficiaryStanding` for the
  pattern.
- Name tests after behaviour (`overshootGoesToTheAgent`, `downlineDoesNotCompress`), not after
  the method under test.
- **Pin rounding behaviour explicitly.** Where accumulated rounding drift is real, assert the
  *bound* and explain the mechanism rather than tuning inputs until the number looks exact.
  There are three such tests already; match their style.
- Testcontainers for the service modules — real Postgres, real Mongo, a real broker. Not H2, not
  an embedded Mongo: the things worth testing (`uuid[]`, `jsonb`, partial indices, `Decimal128`,
  multi-document transactions) only exist in the real servers.
- **There are exactly two mocks in the repository**, and both have their justification written
  where they live: `ProjectionAtomicityIT` (a mid-transaction infrastructure failure cannot be
  requested from a real database on cue) and `AgentDashboardControllerTest` (a `@WebMvcTest`
  slice, where routing and JSON shape really are all that is under test). Before adding a third,
  check the dependency does not belong in an argument instead. `TransactionControllerTest` is the
  worked example of that check coming out the other way: it needed a `RecordClosedTransaction` that
  fails twice and then succeeds, the controller already takes one as a constructor argument, and a
  hand-written stub said it in fewer lines than the mock would have.
- **A test for something that is silently optional must be watched failing.**
  `ProjectionAtomicityIT`, the write side's concurrency test, and both modules'
  `SerializationBoundaryIT` assert behaviour that would quietly not happen if a bean were
  missing; a green test proves nothing there unless the red version was seen first. Say so in
  the Javadoc when you do it.

## CI

`.github/workflows/build.yml` runs on every PR and push to `main`: `spotless:check` →
`mvnw verify` → publish the seed-data summary to the run summary. Unlike auth-lava, there are
no path filters — this is one Maven reactor, so every source change affects the one build.

`claude.yml` and `claude-code-review.yml` mirror auth-lava exactly (`@claude` mentions, and
automatic `/code-review` on PRs). Dependabot is configured for Maven and GitHub Actions,
grouped for minor/patch.
