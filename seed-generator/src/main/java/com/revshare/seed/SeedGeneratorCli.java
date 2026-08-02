package com.revshare.seed;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Command line entry point.
 *
 * <pre>
 *   seed-generator [--seed N] [--agents N] [--start YYYY-MM-DD] [--end YYYY-MM-DD]
 *                  [--out DIR] [--summary-only]
 * </pre>
 *
 * <p>Hand-rolled argument parsing rather than a library. This module has one job and six flags, and the shaded CLI jar
 * is smaller and starts faster without pulling a parser and its reflection machinery in behind it.
 */
public final class SeedGeneratorCli {

    private static final Path DEFAULT_OUTPUT = Path.of("seed-data");

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            printUsage();
            System.exit(2);
        } catch (IOException e) {
            System.err.println("error: could not write seed data: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws IOException {
        SeedConfig config = SeedConfig.defaults();
        Path output = DEFAULT_OUTPUT;
        boolean summaryOnly = false;

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "--help", "-h" -> {
                    printUsage();
                    return;
                }
                case "--summary-only" -> summaryOnly = true;
                case "--seed" -> config = config.withRandomSeed(parseLong(value(args, ++i, flag), flag));
                case "--agents" -> config = config.withAgentCount(parseInt(value(args, ++i, flag), flag));
                case "--start" ->
                    config = config.withWindow(parseDate(value(args, ++i, flag), flag), config.simulationEnd());
                case "--end" ->
                    config = config.withWindow(config.simulationStart(), parseDate(value(args, ++i, flag), flag));
                case "--out" -> output = Path.of(value(args, ++i, flag));
                default -> throw new IllegalArgumentException("unknown option: " + flag);
            }
        }

        System.out.printf(
                "Generating %,d agents from seed %d over %s to %s...%n%n",
                config.agentCount(), config.randomSeed(), config.simulationStart(), config.simulationEnd());

        BrokerageSeed seed = BrokerageSeed.generate(config);
        System.out.println(seed.summary().render());

        if (summaryOnly) {
            System.out.println("(--summary-only: nothing written)");
            return;
        }

        new SeedWriter().write(seed, output);
        System.out.printf("Wrote agents.json, transactions.json and manifest.json to %s%n", output.toAbsolutePath());
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args[index];
    }

    private static long parseLong(String raw, String flag) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " expects an integer, got '" + raw + "'");
        }
    }

    private static int parseInt(String raw, String flag) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " expects an integer, got '" + raw + "'");
        }
    }

    private static LocalDate parseDate(String raw, String flag) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(flag + " expects YYYY-MM-DD, got '" + raw + "'");
        }
    }

    private static void printUsage() {
        SeedConfig defaults = SeedConfig.defaults();
        System.out.println("""
                Generates a synthetic brokerage: agent roster, sponsorship tree, and closed
                transactions. Output is fully determined by --seed. Contains no real data.

                Usage:
                  seed-generator [options]

                Options:
                  --seed N            random seed (default %d)
                  --agents N          number of agents (default %d)
                  --start YYYY-MM-DD  earliest join date (default %s)
                  --end YYYY-MM-DD    end of the transaction window (default %s)
                  --out DIR           output directory (default ./seed-data)
                  --summary-only      print the summary without writing files
                  --help              show this message
                """.formatted(
                        defaults.randomSeed(), defaults.agentCount(),
                        defaults.simulationStart(), defaults.simulationEnd()));
    }

    private SeedGeneratorCli() {}
}
