package com.revshare.seed;

import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the agent roster and the sponsorship tree it hangs on.
 *
 * <h2>Why the tree is grown by preferential attachment</h2>
 *
 * <p>Choosing each new agent's sponsor uniformly at random produces a wide, shallow tree in which almost nobody is more
 * than two or three levels below anybody, and the tier 3 to tier 5 rules never fire. Real referral networks do not look
 * like that: recruiting is concentrated in a few prolific sponsors, and chains run deep.
 *
 * <p>So a sponsor is drawn with probability proportional to {@code (1 + recruitsSoFar) ^ alpha}. The result is the
 * hub-and-chain shape the revenue share program is designed around, and it reliably generates chains deeper than the
 * program's five-level reach, which is exactly the boundary worth having data for.
 *
 * <p>Agents are enrolled in join-date order, which makes the "a sponsor must already exist" constraint hold by
 * construction rather than by a retry loop.
 */
final class AgentRosterGenerator {

    private final SeedConfig config;
    private final SeedRandom random;

    AgentRosterGenerator(SeedConfig config, SeedRandom random) {
        this.config = config;
        this.random = random;
    }

    List<Agent> generate() {
        List<LocalDate> joinDates = drawJoinDates();
        List<Agent> roster = new ArrayList<>(config.agentCount());
        List<Recruiter> recruiters = new ArrayList<>(config.agentCount());

        for (int i = 0; i < config.agentCount(); i++) {
            String firstName = random.pick(NameCatalog.FIRST_NAMES);
            String lastName = random.pick(NameCatalog.LAST_NAMES);
            String email = NameCatalog.email(firstName, lastName, i);
            AgentId id = AgentId.of(random.nextUuid());
            LocalDate joinedOn = joinDates.get(i);

            Agent agent;
            if (i < config.founderCount()) {
                agent = Agent.enroll(id, firstName, lastName, email, joinedOn);
            } else {
                Recruiter sponsor = random.pickWeighted(recruiters, Recruiter::attachmentWeight);
                sponsor.recordRecruit();
                agent = Agent.enrollSponsoredBy(
                        id,
                        firstName,
                        lastName,
                        email,
                        joinedOn,
                        sponsor.agent().id(),
                        sponsor.agent().sponsorshipPath());
            }

            roster.add(agent);
            recruiters.add(new Recruiter(agent, config.sponsorAttachmentAlpha()));
        }

        applyEliteStatus(roster);
        applyTerminations(roster);
        return List.copyOf(roster);
    }

    /**
     * Grants Elite status to a slice of the roster.
     *
     * <p>Assigned at random rather than derived from production, which is an acknowledged simplification: the real
     * status is granted on a production review this simulator does not model. Deriving it from capping would be worse
     * for a fixture, because every capped agent would then be Elite and the standard $285 post-cap fee would never
     * appear in the data at all. A fixed proportion keeps both fee schedules populated.
     */
    private void applyEliteStatus(List<Agent> roster) {
        for (Agent agent : roster) {
            if (random.nextChance(config.eliteStatusProbability())) {
                agent.grantEliteStatus();
            }
        }
    }

    /**
     * Join dates, ascending, with the founders placed first.
     *
     * <p>Sorting is what lets sponsorship be assigned in a single forward pass: every candidate sponsor considered for
     * an agent necessarily joined on or before them.
     */
    private List<LocalDate> drawJoinDates() {
        List<LocalDate> dates = new ArrayList<>(config.agentCount());

        // Founders open the brokerage together.
        for (int i = 0; i < config.founderCount(); i++) {
            dates.add(config.simulationStart());
        }

        // Everyone else joins across the first three quarters of the window, leaving the
        // last quarter clear so late joiners still have time to produce transactions.
        LocalDate recruitingEnd = config.simulationStart().plusDays((long)
                (0.75 * java.time.temporal.ChronoUnit.DAYS.between(config.simulationStart(), config.simulationEnd())));

        List<LocalDate> later = new ArrayList<>();
        for (int i = config.founderCount(); i < config.agentCount(); i++) {
            later.add(random.nextDate(config.simulationStart().plusDays(1), recruitingEnd));
        }
        later.sort(Comparator.naturalOrder());
        dates.addAll(later);

        return dates;
    }

    /**
     * Retires a fraction of the roster.
     *
     * <p>Departures are the point of the exercise, not decoration. An agent who leaves from the middle of a chain is
     * the case that separates a correct implementation of the downline rules from one that quietly compresses the tree,
     * and the generated data has to contain that case for anything downstream to prove it handles it.
     *
     * <p>Nobody leaves within their first six months, so every terminated agent has a plausible stretch of production
     * behind them.
     */
    private void applyTerminations(List<Agent> roster) {
        for (Agent agent : roster) {
            if (!random.nextChance(config.terminationProbability())) {
                continue;
            }
            LocalDate earliest = agent.joinedOn().plusMonths(6);
            if (!earliest.isBefore(config.simulationEnd())) {
                continue;
            }
            agent.terminate(random.nextDate(earliest, config.simulationEnd()));
        }
    }

    /** A candidate sponsor and its running attachment weight. */
    private static final class Recruiter {
        private final Agent agent;
        private final double alpha;
        private int recruits;

        Recruiter(Agent agent, double alpha) {
            this.agent = agent;
            this.alpha = alpha;
        }

        Agent agent() {
            return agent;
        }

        void recordRecruit() {
            recruits++;
        }

        /** Rich get richer. The {@code 1 +} gives every agent a chance at their first recruit. */
        double attachmentWeight() {
            return Math.pow(1.0 + recruits, alpha);
        }
    }
}
