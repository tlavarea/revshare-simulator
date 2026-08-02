package com.revshare.seed;

import java.util.List;
import java.util.Locale;

/**
 * Invented names for invented agents.
 *
 * <p>Two deliberate choices about the fake data this produces. Email addresses use the {@code .test} top-level domain,
 * which RFC 2606 reserves permanently for exactly this purpose and which can never resolve, so a misconfigured
 * environment cannot mail a real person. And property references are opaque synthetic identifiers rather than
 * addresses, because a plausible-looking street address is a real address belonging to a real household somewhere.
 *
 * <p>The name lists are common given and family names combined at random. Any resemblance to a particular person is
 * arithmetic.
 */
final class NameCatalog {

    private NameCatalog() {}

    static final List<String> FIRST_NAMES = List.of(
            "Amara", "Bianca", "Caleb", "Dara", "Elena", "Felix", "Grace", "Hugo", "Imani", "Jonah", "Kiran", "Leila",
            "Mateo", "Nadia", "Omar", "Priya", "Quentin", "Rosa", "Samuel", "Tessa", "Ulises", "Vera", "Wesley",
            "Ximena", "Yusuf", "Zora", "Adrian", "Bethany", "Cyrus", "Delia", "Ethan", "Farah", "Gideon", "Hana",
            "Ivan", "Jocelyn", "Kwame", "Lucia", "Marcus", "Noor", "Oscar", "Paloma", "Rafael", "Simone", "Tomas",
            "Uma", "Victor", "Willa");

    static final List<String> LAST_NAMES = List.of(
            "Abbott",
            "Barros",
            "Castellanos",
            "Dunbar",
            "Eriksen",
            "Farrell",
            "Gutierrez",
            "Halloran",
            "Ibarra",
            "Jensen",
            "Kowalski",
            "Laurent",
            "Moreno",
            "Nakamura",
            "Okonkwo",
            "Pashley",
            "Quintero",
            "Rasmussen",
            "Sandoval",
            "Thackeray",
            "Ueda",
            "Valdez",
            "Whitfield",
            "Xiong",
            "Yarborough",
            "Zimmerman",
            "Ashford",
            "Beaumont",
            "Carrington",
            "Delacroix",
            "Ellsworth",
            "Fontaine",
            "Grimaldi",
            "Hawthorne",
            "Ingersoll",
            "Jaramillo",
            "Kensington",
            "Lindqvist",
            "Mancuso",
            "Novotny",
            "Ortega",
            "Pemberton",
            "Radcliffe",
            "Stavros");

    /**
     * A never-deliverable address, unique per agent.
     *
     * <p>The ordinal suffix matters: 48 first names and 44 last names collide well before 500 agents, and a duplicate
     * email would be an unrealistic fixture for a system whose real counterpart treats it as a natural key.
     */
    static String email(String firstName, String lastName, int ordinal) {
        return "%s.%s%d@example.test"
                .formatted(firstName.toLowerCase(Locale.ROOT), lastName.toLowerCase(Locale.ROOT), ordinal);
    }

    /** An opaque listing reference. Deliberately not an address. */
    static String propertyReference(int ordinal) {
        return "PROP-%07d".formatted(ordinal);
    }
}
