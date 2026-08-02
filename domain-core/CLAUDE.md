# CLAUDE.md — domain-core

The hexagonal core. Domain model, domain services, and driven ports.

## The one hard rule

**This module has zero compile-scoped dependencies. Only the JDK.**

This is not a convention — the module's POM fails the build if Spring, JPA, Hibernate,
Jackson or Kafka appears on its compile classpath, transitively included. If you want to add
a dependency here, the thing you are modelling almost certainly belongs in an adapter.

Concretely, none of these belong in this module: annotations of any kind (`@Entity`,
`@Component`, `@JsonProperty`), anything that reads a clock or a config file, anything that
performs I/O, and anything that knows a wire or table format.

Serialization is a particular trap. It is tempting to point Jackson at these records; the ban
exists to stop it. Doing so makes the wire format an accident of a field name inside an
aggregate, so renaming a field silently breaks a published format. `SeedWriter` shows the
alternative: DTOs declared in the module that owns the format.

## Structure

```
com/revshare/domain/
├── shared/       Money, Percentage
├── agent/        Agent, AgentId, SponsorshipPath, CapYear, AgentStatus, EliteStatus
├── transaction/  ClosedTransaction, TransactionId, TransactionSide
├── commission/   CommissionPlan, CapProgress, CommissionSplit, CommissionCalculator
├── revshare/     RevenueShareTier, RevenueSharePlan, RevenueShareDownline, RevenueShareAward,
│                 RevenueShareDistribution, ProducingAgentPolicy, BeneficiaryStanding,
│                 ForfeitReason, RevenueShareCalculator
├── event/        DomainEvent (sealed) + the four event records
└── port/out/     AgentRepository, CapProgressRepository, RevenueShareLedger,
                  ProductionHistory, DomainEventPublisher
```

## Modelling conventions

**Records for value objects and immutable results. A class for an aggregate root with
behaviour.** `Agent` is the only mutable type in the module, and its transitions are guarded
methods (`terminate`, `grantEliteStatus`) rather than setters, so an illegal transition is
impossible to express. Everything it is built from is immutable. Preserve that split.

**Validate in the compact constructor.** Every record asserts its own invariants on
construction, so an invalid instance cannot exist. `CommissionSplit` goes further and asserts
that the money balances (`agent + company == gross`), which has already caught a real bug.

**Never use `double` or `float` for money.** `Money` wraps `BigDecimal` at scale 2, HALF_UP.
`Percentage` is a separate type at scale 8, because a rate is an intermediate and rounding it
to cents pushes error into everything derived from it. There is deliberately no ambiguous
single-argument `Percentage` factory — `ofPercent` and `ofFraction` are distinct because that
confusion is the classic source of hundred-fold errors in commission code.

**Never hard-code a rate, a cap, or a fee.** They live in `CommissionPlan`, which is passed
into the calculation. Recomputing a two-year-old statement must use the schedule in force
when the deal closed. `RevenueSharePlan` *derives* the five published annual maxima from that
plan rather than restating them — do not replace a derivation with a literal.

## Invariants that must not be broken

**`SponsorshipPath` is frozen at enrolment and never rewritten.** This is the most important
rule in the system. If A sponsors B and B sponsors C, and B leaves, C stays at tier 2 beneath
A forever. Any change that recomputes tier position from live parent links will compress the
tree on departure and pay the wrong rate. There is deliberately no re-parenting API.

**Cap contributions are clamped by the calculator, not by the aggregate.**
`CapProgress.withContribution` *rejects* an over-contribution rather than silently clamping,
because the excess does not vanish — it is paid to the agent. Absorbing it quietly would make
money disappear from the split.

**Forfeited revenue share stays with the company.** It does not roll up to the next eligible
ancestor. Otherwise every agent's earnings would depend on their upline's sales activity.

**The five tier rates sum to exactly the company's split.** `RevenueSharePlan` asserts this at
construction. If a rate changes, that assertion is the thing that will tell you the schedule
no longer hangs together.

## Both calculators are pure functions

`CommissionCalculator` and `RevenueShareCalculator` take no ports, no repositories, and no
clock. Everything arrives as an argument, including resolved eligibility facts
(`BeneficiaryStanding`, assembled by the application layer from repositories).

Keep it that way. It is what makes the entire rulebook testable with no test doubles, and what
makes an event-log replay reproduce the original numbers exactly. **If a calculator needs a new
fact, add it to the argument types — do not inject a port.**

## Testing

74 tests, zero mocks. Match the existing style:

- Behaviour-named tests inside `@Nested` classes grouped by scenario, with `@DisplayName`
  written as a sentence.
- Exercise the boundaries explicitly: the transaction that straddles the cap, the agent who
  joined on 29 February, the beneficiary sitting exactly on the $450 threshold.
- Where rounding drift is real, assert the bound and explain the mechanism in a comment. See
  `eligibleGrossDriftsOnlyByRoundingOnAwkwardAmounts`. Do not quietly pick inputs that make the
  arithmetic come out even and imply it always does — there is a separate test for the exact
  case, and they belong side by side.
