package com.revshare.seed;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.revshare.domain.agent.Agent;
import com.revshare.domain.agent.AgentId;
import com.revshare.domain.transaction.ClosedTransaction;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Writes a generated brokerage to JSON.
 *
 * <h2>Why there are DTOs here</h2>
 *
 * <p>The obvious shortcut is to point Jackson at the domain records and let it reflect over them. That is exactly what
 * {@code domain-core}'s banned-dependency rule exists to prevent. Serializing the domain directly makes the wire format
 * an accident of the field names of an aggregate: renaming a field inside {@code CommissionSplit} silently becomes a
 * breaking change to a published file format, and the core acquires an invisible dependency on the serializer's
 * conventions.
 *
 * <p>So the file format is declared explicitly, right here, as records that exist only to be serialized. The mapping is
 * a few dozen lines, it is the only place the format is defined, and the domain stays free to change shape without
 * breaking a consumer.
 */
public final class SeedWriter {

    private final ObjectMapper mapper;

    public SeedWriter() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // ISO-8601 strings, not epoch numbers. The file is meant to be read by a
                // human deciding whether the fixture looks right.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Omit nulls, so an agent who has not left carries no `terminatedOn` key at
                // all rather than an explicit null. setSerializationInclusion is deprecated
                // in Jackson 2.19; this is the supported replacement.
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Writes {@code agents.json}, {@code transactions.json} and {@code manifest.json} into {@code directory}, creating
     * it if needed.
     */
    public void write(BrokerageSeed seed, Path directory) throws IOException {
        Files.createDirectories(directory);

        mapper.writeValue(
                directory.resolve("agents.json").toFile(),
                seed.agents().stream().map(SeedWriter::toDto).toList());

        mapper.writeValue(
                directory.resolve("transactions.json").toFile(),
                seed.transactions().stream().map(SeedWriter::toDto).toList());

        mapper.writeValue(directory.resolve("manifest.json").toFile(), new ManifestDto(seed.config(), seed.summary()));
    }

    private static AgentDto toDto(Agent agent) {
        return new AgentDto(
                agent.id().value().toString(),
                agent.firstName(),
                agent.lastName(),
                agent.email(),
                agent.joinedOn(),
                agent.sponsorId().map(AgentId::toString).orElse(null),
                // The materialised path is written out because it is the authoritative
                // record of tier position, not a cache of one. A consumer that rebuilt it
                // from sponsor links would get the wrong answer for departed sponsors.
                agent.sponsorshipPath().ancestorsNearestFirst().stream()
                        .map(AgentId::toString)
                        .toList(),
                agent.sponsorshipPath().depth(),
                agent.status().name(),
                agent.eliteStatus().name(),
                agent.terminatedOn().orElse(null));
    }

    private static TransactionDto toDto(ClosedTransaction transaction) {
        return new TransactionDto(
                transaction.id().value().toString(),
                transaction.agentId().value().toString(),
                transaction.closedOn(),
                transaction.salePrice().amount(),
                transaction.grossCommissionIncome().amount(),
                transaction.side().name(),
                transaction.propertyReference());
    }

    record AgentDto(
            String id,
            String firstName,
            String lastName,
            String email,
            LocalDate joinedOn,
            String sponsorId,
            List<String> sponsorshipPath,
            int sponsorshipDepth,
            String status,
            String eliteStatus,
            LocalDate terminatedOn) {}

    record TransactionDto(
            String id,
            String agentId,
            LocalDate closedOn,
            BigDecimal salePrice,
            BigDecimal grossCommissionIncome,
            String side,
            String propertyReference) {}

    /**
     * The reproducibility record. Carries the full configuration alongside the summary, so any generated dataset can be
     * regenerated exactly from the file that describes it.
     */
    record ManifestDto(String description, String dataProvenance, SeedConfig config, SeedSummary summary) {

        ManifestDto(SeedConfig config, SeedSummary summary) {
            this(
                    "Synthetic brokerage generated by revshare-simulator's seed-generator. "
                            + "Regenerate exactly with: seed-generator --seed "
                            + config.randomSeed() + " --agents " + config.agentCount(),
                    "Entirely synthetic. No real agents, sales, addresses or brokerage data. "
                            + "Email addresses use the reserved .test TLD and cannot resolve.",
                    config,
                    summary);
        }
    }
}
