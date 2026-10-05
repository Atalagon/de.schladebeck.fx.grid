package de.schladebeck.fx.grid.showcase.examples;

/** The sample type most examples show. */
public record Person(int id, String name, String city, String department, int age, double salary, boolean active) {

    public Person withName(String value) {
        return new Person(id, value, city, department, age, salary, active);
    }

    public Person withCity(String value) {
        return new Person(id, name, value, department, age, salary, active);
    }

    public Person withSalary(double value) {
        return new Person(id, name, city, department, age, value, active);
    }

    public Person withActive(boolean value) {
        return new Person(id, name, city, department, age, salary, value);
    }
}
