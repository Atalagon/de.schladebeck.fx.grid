package de.schladebeck.fx.grid.showcase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Opens the showcase and every one of its tabs, as a user clicking through it would. */
class ShowcaseSmokeTest {

    @BeforeAll
    static void startToolkit() {
        try {
            Platform.startup(() -> {
            });
        } catch (IllegalStateException alreadyRunning) {
            // started before
        }
        Platform.setImplicitExit(false);
    }

    private static void onFxThread(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        try {
            task.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    @Test
    void everyTabOpensAndStops() throws Exception {
        List<String> opened = new ArrayList<>();
        onFxThread(() -> {
            Stage stage = new Stage();
            Showcase showcase = new Showcase();
            showcase.start(stage);
            TabPane tabs = (TabPane) stage.getScene().lookup(".tab-pane");
            for (Tab tab : tabs.getTabs()) {
                tabs.getSelectionModel().select(tab);
                assertNotNull(tab.getContent(), tab.getText());
                assertTrue(showcase.sourceView().fileNames().contains("Example.java"), tab.getText());
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                opened.add(tab.getText());
            }
            showcase.stop();
            stage.close();
        });
        assertEquals(Showcase.CATEGORIES.stream().mapToInt(category -> category.examples().size()).sum(),
                opened.size());
    }
}
