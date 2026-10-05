package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.TreeGrid;
import de.schladebeck.fx.grid.model.TreeGridModel;
import de.schladebeck.fx.grid.render.CellRenderers;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javafx.scene.Node;

public final class TreeExample extends Example {

    /** A part of the company: its people and budget are those of all parts below it. */
    public record Unit(String name, String kind, int people, double budget, List<Unit> children) {
    }

    private static final String[] KINDS = {"Company", "Division", "Department", "Team", "Person"};
    private static final String[] AREAS = {"Sales", "Research", "Operations", "Finance", "Support", "Marketing",
            "Engineering", "Logistics"};

    public TreeExample() {
        super("Organization", "A TreeGridModel shows items with children as rows; a TreeGrid indents the first column "
                + "and adds the triangles. Click a triangle or double-click a name to open it; on the keyboard, Right "
                + "opens and Left closes or goes up. Click a header to sort: each unit's children are sorted among "
                + "themselves. Shift+click a second header to sort by both.");
    }

    @Override
    public Node create() {
        TreeGridModel<Unit> company = new TreeGridModel<Unit>(Unit::children)
                .column("Name", Unit::name)
                .column("Kind", Unit::kind)
                .column("People", Unit::people)
                .column("Budget", Unit::budget);
        company.setRoots(List.of(unit("FX Grid Ltd", 0, new Random(7))));
        TreeGrid grid = new TreeGrid(company);
        grid.getStyleClass().add("modern"); // styles.css
        grid.getColumn("Name").setWidth(300); // fitting sees the rows that are open when the data arrives
        grid.getColumn("Kind").setPrototypeValue("Department");
        grid.getColumn("Budget").setRenderer(CellRenderers.number("#,##0 €"));
        company.setExpanded(0, true);
        return grid;
    }

    /** A unit and, below a person, the units it is made of, with their people and budgets added up. */
    private static Unit unit(String name, int level, Random random) {
        if (level == KINDS.length - 1) {
            return new Unit(name, KINDS[level], 1, 40_000 + random.nextInt(80_000), List.of());
        }
        List<Unit> children = new ArrayList<>();
        int count = 2 + random.nextInt(level == 0 ? 4 : 5);
        for (int i = 0; i < count; i++) {
            String child = level == KINDS.length - 2 ? SampleData.name(random) : AREAS[random.nextInt(AREAS.length)]
                    + " " + KINDS[level + 1].toLowerCase() + " " + (i + 1);
            children.add(unit(child, level + 1, random));
        }
        return new Unit(name, KINDS[level], children.stream().mapToInt(Unit::people).sum(),
                children.stream().mapToDouble(Unit::budget).sum(), children);
    }
}
