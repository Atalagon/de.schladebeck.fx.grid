package de.schladebeck.fx.grid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.schladebeck.fx.grid.Fx;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ComputedGridModelTest {

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    @Test
    void buildersFromBackgroundThreadsArriveOnTheFxThread() throws Exception {
        ComputedGridModel model = Fx.call(() -> new ComputedGridModel(2, 2, (row, column) -> row + column));
        List<Boolean> onFxThread = new CopyOnWriteArrayList<>();
        Fx.run(() -> model.addGridModelListener(e -> onFxThread.add(Platform.isFxApplicationThread())));

        model.columnNames(column -> "x" + column);
        model.rowNames(row -> "y" + row);
        model.columnTypes(column -> Integer.class);
        Fx.run(model::refresh);

        assertEquals("x1", Fx.call(() -> model.getColumnName(1)));
        assertEquals("y1", Fx.call(() -> model.getRowName(1)));
        assertEquals(Integer.class, Fx.call(() -> model.getColumnClass(1)));
        assertEquals(List.of(true, true, true, true), onFxThread);
    }
}
