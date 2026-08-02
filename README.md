# Agent Revenue Share & Commission Cap Simulator

An event-driven, CQRS reference implementation of a real-estate brokerage's agent
compensation programme: an 85/15 commission split with a $12,000 annual cap, a flat
per-transaction fee after capping, and a five-tier revenue share programme paid out of the
company's share.

The interesting part of this domain is not the arithmetic, it is the set of rules that make
the arithmetic non-obvious: the cap year runs from each agent's own anniversary rather than
the calendar; a single closing can straddle the cap and be priced two ways at once; and the
sponsorship hierarchy that revenue share is paid along must survive an agent in the middle
of it leaving the company.

> **All data in this repository is synthetic.** The commission structure modelled here is
> published, public information. Every agent, sale, address and dollar figure is generated
> by `seed-generator` from a random seed. There is no real brokerage data of any kind.

## Status

| Module | State |
| --- | --- |
| `domain-core` | Complete. Framework-free hexagonal core, 74 unit tests. |
| `seed-generator` | Complete. Deterministic synthetic data, CLI, 19 tests. |
| `commission-service` | Not started. Write side: Postgres, JPA, Kafka producer, outbox. |
| `reporting-service` | Not started. Read side: Kafka consumer, MongoDB projections. |

## Quick start

Requires **JDK 21**. The build fails fast on anything outside 21–24, because Spring Boot 3.5
cannot read class files from newer JDKs.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
./mvnw verify

# Generate a synthetic brokerage and see what the domain rules make of it
./mvnw -q -pl seed-generator exec:java \
  -Dexec.mainClass=com.revshare.seed.SeedGeneratorCli \
  -Dexec.args="--summary-only"
```

Infrastructure for the (not yet written) services:

```bash
docker compose up -d      # Postgres, MongoDB, Kafka
```

## The business rules

| Rule | Value |
| --- | --- |
| Commission split | 85% agent / 15% company |
| Annual cap | $12,000 of company dollar, per agent, per **anniversary** year |
| Post-cap transaction fee | $285, or $129 for Elite agents |
| Revenue share tiers | 5% / 4% / 3% / 2% / 1% of gross, five levels deep |
| Annual maximum per tier, per contributing agent | $4,000 / $3,200 / $2,400 / $1,600 / $800 |
| Tier unlock | Tier 1 automatic; tiers 2–5 need producing frontline agents |
| Producing Agent Policy | $450 of gross commission in the trailing 6 months |

### The numbers are derived, not typed in

The published per-tier annual maxima are not independent figures. They fall out of the other
three:

```
an agent caps at                 $12,000 of company dollar
which at a 15% split requires    $12,000 / 0.15 = $80,000 of gross commission
so tier 1, paying 5% of gross,   $80,000 × 5%   = $4,000  ← the published tier 1 maximum
   tier 2, paying 4%                            = $3,200  ← the published tier 2 maximum
   tier 3, 3%                                   = $2,400
   tier 4, 2%                                   = $1,600
   tier 5, 1%                                   =   $800
```

And the five tier rates sum to 5+4+3+2+1 = **15%**, exactly the company's share. That is the
precise sense in which revenue share is paid *out of* the company dollar rather than on top
of it: a fully unlocked five-deep upline can consume all of it and never a cent more.

`RevenueSharePlan` derives all five maxima from the commission plan and asserts the rate sum
against it at construction, so the schedule cannot drift out of internal consistency. Change
the cap to $15,000 and every tier maximum moves correctly on its own
([`RevenueSharePlanTest`](domain-core/src/test/java/com/revshare/domain/revshare/RevenueSharePlanTest.java)).

### Rules that are easy to get wrong

**The cap year is anniversary-based.** An agent who joined on 14 March caps against 14 March
to 13 March. Modelling this as a calendar year would change which transactions contribute to
the cap, and therefore which ones generate revenue share. `CapYear` also handles the 29
February case, where `plusYears` clamps to 28 February and a naive window fails to contain
the date it was derived from.

**A closing can straddle the cap.** If $1,000 of cap remains and a $20,000 closing arrives,
the company takes $1,000, not the full $3,000 split — and the $2,000 difference is paid to
the agent, not carried forward. That closing is priced under the split, *not* the flat fee;
the fee begins on the next one. Charging both would collect more than the cap in one year.

**Only the pre-cap slice funds revenue share.** The same straddling closing generates company
dollar on only part of its gross. `CommissionSplit.revenueShareEligibleGross` carries that
slice forward, which is what makes each agent's annual contribution to their upline bounded
by exactly $80,000 of gross.

**The hierarchy does not compress.** If A sponsors B and B sponsors C, and B leaves, C stays
at *tier 2* beneath A forever. A naive implementation walking live parent pointers would
promote C to tier 1 and pay A the 5% rate instead of the correct 4%. This is why
`SponsorshipPath` is a materialised path frozen at enrolment and never rewritten — see
[`SponsorshipHierarchyTest`](domain-core/src/test/java/com/revshare/domain/agent/SponsorshipHierarchyTest.java).

**Forfeitures do not roll up.** An agent failing the Producing Agent Policy forfeits their
share to the company; it is not redistributed to the next eligible ancestor. Otherwise every
agent's earnings would depend on their upline's sales activity.

## Architecture

```
                        writes                          reads
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

### Hexagonal, enforced mechanically

`domain-core` has **zero compile-scoped dependencies**. Not "Spring is discouraged here" as a
convention — the module's POM fails the build if Spring, JPA, Hibernate, Jackson or Kafka
appears on its compile classpath, transitively included:

```xml
<bannedDependencies>
  <excludes>
    <exclude>org.springframework*:*</exclude>
    <exclude>jakarta.persistence:*</exclude>
    ...
```

The root POM *imports* the `spring-boot-dependencies` BOM rather than inheriting from
`spring-boot-starter-parent`, so modules get consistent versions without the parent's plugin
bindings reaching the core.

### Both calculators are pure functions

`CommissionCalculator` and `RevenueShareCalculator` take no ports, no repositories and no
clock. Everything they need arrives as arguments, including the resolved eligibility facts
for each beneficiary (`BeneficiaryStanding`). Two consequences:

- The entire rulebook is exercisable in plain unit tests with **no mocks at all**. There is
  not a single test double in the 74 domain tests.
- Replaying an event log reproduces the original numbers exactly, because there is nothing
  ambient for the result to depend on.

Repository access is pushed outward to the driven ports in `domain/port/out`, which the
service modules will implement.

### Layout

```
domain-core/src/main/java/com/revshare/domain/
├── shared/       Money, Percentage           — BigDecimal only, no floating point
├── agent/        Agent, SponsorshipPath, CapYear, EliteStatus
├── transaction/  ClosedTransaction
├── commission/   CommissionPlan, CapProgress, CommissionSplit, CommissionCalculator
├── revshare/     RevenueShareTier, RevenueSharePlan, RevenueShareDownline,
│                 ProducingAgentPolicy, RevenueShareCalculator, RevenueShareAward
├── event/        TransactionClosed → CommissionCalculated → CapThresholdReached
│                 → RevenueShareDistributed   (sealed interface, exhaustive switch)
└── port/out/     AgentRepository, CapProgressRepository, RevenueShareLedger,
                  ProductionHistory, DomainEventPublisher
```

### Notes carried in the ports for the adapters to honour

These are the concurrency and consistency decisions, written down where the implementer will
see them:

- **`CapProgressRepository`** — cap progress is read-modify-write. Two closings priced
  concurrently for one agent would both read the same balance and let the agent earn past the
  cap. Requires optimistic locking or `SELECT … FOR UPDATE`, with caller retry.
- **`DomainEventPublisher`** — writing to Postgres and then to Kafka is a dual write; a crash
  between them leaves the read side permanently behind with nothing to detect it. The intended
  adapter is a transactional outbox.
- **`RevenueShareLedger`** — append-only, and idempotent on transaction id, because the read
  side consumes at least once and a redelivered event must not double-pay an upline.
- **Partitioning** — commission events are keyed by agent, so one agent's closings stay
  ordered (cap progress is cumulative). Revenue share events are keyed by the *contributor*,
  because one event concerns up to five beneficiaries and none of them can own the ordering.

### Why MongoDB for the read side

The read model is one agent's dashboard: cap progress, earnings, and their downline grouped
into five tiers. It is a naturally hierarchical document, read far more often than it
changes, and expensive to assemble from the normalised write model — a recursive CTE per
dashboard load. Projecting it once into a document and serving it whole is the case where a
document store earns its place, as opposed to being a cache with extra steps.

## The seed generator

Produces a complete synthetic brokerage: an agent roster, a sponsorship tree, and a
chronological stream of closed transactions.

```bash
./mvnw -q -pl seed-generator exec:java \
  -Dexec.mainClass=com.revshare.seed.SeedGeneratorCli \
  -Dexec.args="--seed 42 --agents 500 --out seed-data"
```

**Deterministic.** The seed fixes the output completely, down to individual UUIDs. Built on
`java.util.Random`, whose algorithm is specified exactly in the javadoc and stable across JDK
versions and platforms, so a run in CI on Linux is byte-identical to a run on a laptop.
(`UUID.randomUUID()` would break this — it draws from an unseedable `SecureRandom` — so v4
UUIDs are assembled from the seeded stream by hand.)

**Shaped like reality where it matters.** Agent production is drawn once per agent and is
heavily skewed, because a generator that redraws per transaction regresses everyone to the
median and the population of capping agents — the only population revenue share pays on —
disappears. Sponsors are chosen by preferential attachment, because uniform selection makes a
wide, shallow tree where tiers 3–5 never fire. Closings are seasonal, so cap years fill
unevenly and an agent's anniversary month actually matters.

**Measured, not assumed.** The generator runs its own output through the real
`CommissionCalculator` and reports what happened. A fixture that looks plausible can still be
useless — tune production slightly too low and nothing ever caps, so every cap-related branch
goes untested against realistic data, and nothing about the JSON reveals it.
`BrokerageSeedTest` asserts the headline figures stay in band.

Default configuration, 500 agents over four years:

```
Roster
  agents                          500
  founders (no sponsor)           8
  terminated                      48
  mid-chain departures            18   <- upline and downline both present
  deepest sponsorship chain       7 levels
  agents 5+ levels deep           31

Transactions
  closings                        4,541
  gross commission income         $63,925,782.06

Commission outcomes
  agents who capped at least once 158
  cap events (agent x cap year)   256
  closings straddling the cap     256
  post-cap closings (flat fee)    955
  company dollar collected        $7,432,269.83
  agent earnings                  $56,493,512.23

Revenue share inputs
  revenue-share-eligible gross    $47,911,798.83
  agents below the $450 threshold 131
```

That "mid-chain departures" line is the one that matters most: 18 agents left from the middle
of a chain, with both an upline and a downline. That is the exact configuration separating a
correct downline implementation from one that silently compresses the tree.

### Synthetic data guarantees

- Names are generic given and family names combined at random.
- Email addresses use the `.test` TLD, which RFC 2606 reserves permanently and which can
  never resolve — a misconfigured environment cannot mail a real person.
- Properties are opaque references (`PROP-0000123`), never addresses. A plausible-looking
  street address is a real address belonging to a real household somewhere.

## Documented assumptions

Points the published schedule does not settle, resolved deliberately and recorded so they
read as decisions rather than oversights:

| Assumption | Resolution | Where |
| --- | --- | --- |
| Is the cap-crossing transaction also charged the flat fee? | No. The fee starts on the next one; charging both would exceed the cap. | `CommissionCalculator` |
| What are the tier 2–5 unlock thresholds? | 5 / 10 / 15 / 20 producing frontline agents. The published schedule states the requirement without fixing numbers. | `RevenueShareTier` |
| What does "production" mean in the $450 policy? | Gross commission income from closings in the window — the only measure independent of cap status. | `ProducingAgentPolicy` |
| Where do forfeited amounts go? | They stay with the company; they do not roll up. | `ProducingAgentPolicy` |
| Sub-cent rounding across five tiers | Each tier rounds independently, so the five can exceed the company dollar by ≤2.5¢. Accepted and bounded rather than corrected by largest-remainder, which would make one agent's payment depend on four others' rounding. | `RevenueShareDistribution` |

## Testing

```bash
./mvnw verify           # 96 tests
./mvnw -pl domain-core test
```

- **JUnit 5 + AssertJ**, no mocking framework in the domain — the pure calculators do not
  need one.
- Tests are named as behaviour (`overshootGoesToTheAgent`,
  `downlineDoesNotCompress`) rather than after methods.
- Rounding behaviour is pinned explicitly, including a test that asserts the *bound* on
  accumulated drift rather than pretending the arithmetic is exact.
- Testcontainers integration tests will accompany the service modules.

## Licence

Portfolio project. Not affiliated with, endorsed by, or containing data from any brokerage.
