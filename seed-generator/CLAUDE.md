# CLAUDE.md — seed-generator

Generates a complete synthetic brokerage: an agent roster, the sponsorship tree it forms, and
a chronological stream of closed transactions. Depends on `domain-core`; owns the JSON file
format.

## The determinism contract

**A seed reproduces a brokerage exactly, on any machine, down to individual UUIDs.** That is
what makes generated data usable as a test fixture rather than a demo, and it is easy to break
without noticing. The rules:

- **One `SeedRandom`, threaded through every stage in a fixed order.** `BrokerageSeed.generate`
  creates it once and passes it to the roster generator, then the transaction generator.
  Introducing a second generator, or reordering the stages, changes the output for an unchanged
  seed.
- **Built on `java.util.Random` deliberately.** Its LCG and its `nextGaussian` are specified
  exactly in the Javadoc and stable across JDK versions and platforms. Do not "modernise" it to
  `RandomGenerator.of(...)` or `ThreadLocalRandom` — a CI run on Linux would stop matching a
  laptop run.
- **Never call `UUID.randomUUID()`.** It draws from an unseedable `SecureRandom`. Use
  `SeedRandom.nextUuid()`, which assembles a well-formed v4 UUID from the seeded stream.
- **No `Instant.now()`, no `LocalDate.now()`.** All dates derive from `SeedConfig`'s window.

**Adding or reordering any draw changes the output for every existing seed.** That is
acceptable when intentional, but it invalidates any dataset someone committed against the old
behaviour. Say so in the commit message.

`BrokerageSeedTest.sameSeedProducesIdenticalOutput` guards this. It is not a formality.

## Realism is a functional requirement, not decoration

The distribution parameters are tuned so the generated data actually reaches the states the
domain rules are about. Three properties carry that weight:

- **Production is drawn once per agent**, not per transaction. Redrawing per transaction
  regresses everyone to the median over a few years and the population of capping agents —
  the only population revenue share pays on — disappears.
- **Sponsors are chosen by preferential attachment**, weighted by existing recruit count.
  Uniform selection produces a wide, shallow tree where tiers 3–5 never fire.
- **Closings are seasonal**, so cap years fill unevenly and an agent's anniversary month
  actually matters — which is the whole point of an anniversary-based cap window.

`medianAnnualClosings` is the sensitive knob. It is set to **2.5**, which lands roughly 15% of
agents at the cap per year. Raising it much above 3.0 tips most closings onto the post-cap flat
fee and leaves the 85/15 split barely exercised; below 2.0 there are too few capped agents to
generate meaningful revenue share. If you change it, re-run `--summary-only` and check the
bands.

## The fixture has a fitness test

`SeedSummary` runs the generator's own output through the real `CommissionCalculator` and
reports what happened. This exists because a plausible-looking dataset can still be useless,
and nothing about the JSON reveals it.

`BrokerageSeedTest`'s `FixtureFitness` nested class asserts the headline figures stay in band:
a realistic minority of agents cap, closings straddle the cap, chains run at least five deep,
and — most importantly — **`midChainDepartures` is positive**. That last one is the exact
configuration separating a correct downline implementation from one that compresses the tree,
and if the generator stops producing it, nothing downstream can demonstrate the rule.

When you add a generator feature, add the corresponding fitness assertion.

## Synthetic data guarantees

These are promises made in the README and the manifest. Do not weaken them:

- Names are generic given and family names combined at random.
- **Email addresses must use the `.test` TLD**, which RFC 2606 reserves permanently and which
  can never resolve. A misconfigured environment must not be able to mail a real person.
- **Properties are opaque references (`PROP-0000123`), never addresses.** A plausible-looking
  street address is a real address belonging to a real household somewhere.

## File format

`SeedWriter` owns it, via DTOs declared in that file. **Do not serialize `domain-core` types
directly** — see `domain-core/CLAUDE.md` for why. If a field changes shape, the mapping is the
one place to change it.

Output is `agents.json`, `transactions.json`, and `manifest.json`. The manifest carries the
full `SeedConfig` alongside the summary, so any dataset can be regenerated exactly from the
file describing it. Generated output is gitignored (`/seed-data/`) — it is reproducible from a
seed, so committing it is redundant.

## Adding a config knob

`SeedConfig` is a record with `with*` copy methods. Adding a component means touching every
`with*` method — they are exhaustive by design so the compiler catches an omission. Add
validation to the compact constructor, a default to `defaults()`, and a `@param` line. Wire a
CLI flag in `SeedGeneratorCli` only if it is worth exposing.
