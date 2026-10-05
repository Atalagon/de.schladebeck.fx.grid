package de.schladebeck.fx.grid.showcase.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Repeatable sample people. */
final class SampleData {

    private static final String[] FIRST = {"Anna", "Ben", "Clara", "David", "Eva", "Felix", "Greta", "Hannes",
            "Ida", "Jonas", "Klara", "Lukas", "Mia", "Noah", "Olivia", "Paul"};
    private static final String[] LAST = {"Becker", "Fischer", "Hoffmann", "Koch", "Meyer", "Müller", "Richter",
            "Schäfer", "Schmidt", "Schneider", "Schulz", "Wagner", "Weber", "Wolf", "Zimmermann", "Neumann"};
    private static final String[] CITIES = {"Berlin", "Hamburg", "Munich", "Cologne", "Frankfurt", "Stuttgart",
            "Düsseldorf", "Leipzig", "Dortmund", "Bremen", "Dresden", "Hanover"};
    private static final String[] DEPARTMENTS = {"Engineering", "Sales", "Marketing", "Finance", "Support",
            "Operations", "Research"};

    private SampleData() {
    }

    static List<Person> people(int count) {
        Random random = new Random(42);
        List<Person> people = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            people.add(new Person(i + 1, name(random),
                    CITIES[random.nextInt(CITIES.length)],
                    DEPARTMENTS[random.nextInt(DEPARTMENTS.length)],
                    20 + random.nextInt(45),
                    Math.round((30_000 + random.nextDouble() * 120_000) * 100) / 100.0,
                    random.nextInt(5) != 0));
        }
        return people;
    }

    /** A first and a last name. */
    static String name(Random random) {
        return FIRST[random.nextInt(FIRST.length)] + " " + LAST[random.nextInt(LAST.length)];
    }

    private static final String[] MORE_NOTES = {
            " Prefers remote work on Fridays.",
            " Leads the quarterly planning and the hiring for the new team.",
            " Speaks three languages and mentors two juniors.",
            " Currently on a sabbatical until spring, back in April."};

    /** One to five sentences, long enough to be cut off or wrapped. */
    static String longNotes(Person person) {
        StringBuilder notes = new StringBuilder(person.name() + " works in " + person.department() + " in "
                + person.city() + " and joined in " + (2026 - person.age() / 4) + ".");
        for (int i = 0; i < person.id() % 5; i++) {
            notes.append(MORE_NOTES[i]);
        }
        return notes.toString();
    }
}
