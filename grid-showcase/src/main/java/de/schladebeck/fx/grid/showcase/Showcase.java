package de.schladebeck.fx.grid.showcase;

import de.schladebeck.fx.grid.showcase.examples.BenchmarkExample;
import de.schladebeck.fx.grid.showcase.examples.ColumnsExample;
import de.schladebeck.fx.grid.showcase.examples.ContextMenuExample;
import de.schladebeck.fx.grid.showcase.examples.Example;
import de.schladebeck.fx.grid.showcase.examples.FileSystemExample;
import de.schladebeck.fx.grid.showcase.examples.FilterExample;
import de.schladebeck.fx.grid.showcase.examples.FontsAndHeadersExample;
import de.schladebeck.fx.grid.showcase.examples.FormEditingExample;
import de.schladebeck.fx.grid.showcase.examples.HeatMapExample;
import de.schladebeck.fx.grid.showcase.examples.HelloExample;
import de.schladebeck.fx.grid.showcase.examples.HugeDataExample;
import de.schladebeck.fx.grid.showcase.examples.InlineEditingExample;
import de.schladebeck.fx.grid.showcase.examples.LiveDataExample;
import de.schladebeck.fx.grid.showcase.examples.MasterDetailExample;
import de.schladebeck.fx.grid.showcase.examples.MultiLineExample;
import de.schladebeck.fx.grid.showcase.examples.ObjectsAsColumnsExample;
import de.schladebeck.fx.grid.showcase.examples.ObservableListExample;
import de.schladebeck.fx.grid.showcase.examples.PagedExample;
import de.schladebeck.fx.grid.showcase.examples.ProjectPlanExample;
import de.schladebeck.fx.grid.showcase.examples.RecordsExample;
import de.schladebeck.fx.grid.showcase.examples.RenderingExample;
import de.schladebeck.fx.grid.showcase.examples.SelectionExample;
import de.schladebeck.fx.grid.showcase.examples.StylingExample;
import de.schladebeck.fx.grid.showcase.examples.TreeExample;
import de.schladebeck.fx.grid.showcase.examples.UpdateStressExample;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SingleSelectionModel;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/// One tab per example, grouped by category in the menu bar. Ctrl+Tab and Ctrl+Shift+Tab cycle through the tabs,
/// the View menu shows or hides the source code of the current example.
///
/// Only the selected example runs: switching tabs stops the previous one and builds the next one fresh.
public final class Showcase extends Application {

    /** A menu of the menu bar. Benchmarks are examples too, they come last in the Performance menu. */
    record Category(String name, List<Example> examples) {
    }

    /// Files several examples share, offered next to the example's source when it names them. The base class comes
    /// last, it is the same for every example.
    private static final List<String> HELPERS = List.of("Person.java", "SampleData.java", "styles.css", "Example.java");

    static final List<Category> CATEGORIES = List.of(
            new Category("Data", List.of(
                    new HelloExample(),
                    new RecordsExample(),
                    new ObservableListExample(),
                    new ObjectsAsColumnsExample(),
                    new PagedExample(),
                    new LiveDataExample())),
            new Category("Rows and columns", List.of(
                    new ColumnsExample(),
                    new FilterExample(),
                    new MultiLineExample())),
            new Category("Trees", List.of(
                    new TreeExample(),
                    new ProjectPlanExample(),
                    new FileSystemExample())),
            new Category("Interaction", List.of(
                    new SelectionExample(),
                    new ContextMenuExample(),
                    new MasterDetailExample(),
                    new FormEditingExample(),
                    new InlineEditingExample())),
            new Category("Look", List.of(
                    new RenderingExample(),
                    new FontsAndHeadersExample(),
                    new StylingExample(),
                    new HeatMapExample())),
            new Category("Performance", List.of(
                    HugeDataExample.rows(),
                    HugeDataExample.columns(),
                    new UpdateStressExample(),
                    new BenchmarkExample())));

    private final TabPane tabs = new TabPane();
    private final SourceView source = new SourceView();
    private final ToggleGroup menuSelection = new ToggleGroup();
    /** One button per example, wrapping into as many rows as the window needs; the tab pane's header is hidden. */
    private final FlowPane tabButtons = new FlowPane();
    private final ToggleGroup tabSelection = new ToggleGroup();

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        MenuBar menus = new MenuBar();
        for (Category category : CATEGORIES) {
            Menu menu = new Menu(category.name());
            if (!tabButtons.getChildren().isEmpty()) {
                tabButtons.getChildren().add(new Separator(Orientation.VERTICAL));
            }
            for (Example example : category.examples()) {
                Tab tab = new Tab(example.title());
                tab.setUserData(example);
                tabs.getTabs().add(tab);
                ToggleButton button = new ToggleButton(example.title());
                button.setToggleGroup(tabSelection);
                button.setUserData(tab);
                button.setOnAction(e -> {
                    button.setSelected(true); // a second click does not leave no example selected
                    tabs.getSelectionModel().select(tab);
                });
                tabButtons.getChildren().add(button);
                if (example instanceof BenchmarkExample) {
                    menu.getItems().add(new SeparatorMenuItem());
                }
                RadioMenuItem item = new RadioMenuItem(example.title());
                item.setToggleGroup(menuSelection);
                item.setUserData(tab);
                item.setOnAction(e -> tabs.getSelectionModel().select(tab));
                menu.getItems().add(item);
            }
            menus.getMenus().add(menu);
        }

        SplitPane main = new SplitPane(tabs, source);
        main.setOrientation(Orientation.VERTICAL);
        main.setDividerPositions(0.65);
        menus.getMenus().add(viewMenu(main));

        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("examples");
        tabButtons.getStyleClass().add("example-buttons");
        String first = getParameters() == null ? null : getParameters().getNamed().get("example"); // a restart
        tabs.getTabs().stream().filter(tab -> tab.getText().equals(first)).findFirst()
                .ifPresent(tab -> tabs.getSelectionModel().select(tab));
        tabs.getSelectionModel().selectedItemProperty().addListener((o, old, tab) -> show(old, tab));
        show(null, tabs.getSelectionModel().getSelectedItem());

        Scene scene = new Scene(new BorderPane(main, new VBox(menus, tabButtons), null, null, null), 1400, 900);
        scene.getStylesheets().addAll(Showcase.class.getResource("showcase.css").toExternalForm(),
                Example.class.getResource("styles.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle(Launcher.vsync() ? "FX Grid showcase" : "FX Grid showcase, without vsync");
        setIcon(stage);
        stage.show();
    }

    @Override
    public void stop() {
        example(tabs.getSelectionModel().getSelectedItem()).stop();
    }

    /** The showcase icon in all its sizes; the window, the task bar and the task switcher pick one each. */
    static void setIcon(Stage stage) {
        for (int size : new int[] {16, 24, 32, 48, 64, 128, 256}) {
            stage.getIcons().add(new Image(Showcase.class.getResource("icon-" + size + ".png").toExternalForm()));
        }
    }

    private Menu viewMenu(SplitPane main) {
        SingleSelectionModel<Tab> selection = tabs.getSelectionModel();
        MenuItem next = new MenuItem("Next example");
        next.setAccelerator(KeyCombination.valueOf("Shortcut+Tab"));
        next.setOnAction(e -> selection.select((selection.getSelectedIndex() + 1) % tabs.getTabs().size()));
        MenuItem previous = new MenuItem("Previous example");
        previous.setAccelerator(KeyCombination.valueOf("Shortcut+Shift+Tab"));
        previous.setOnAction(
                e -> selection.select(Math.floorMod(selection.getSelectedIndex() - 1, tabs.getTabs().size())));

        CheckMenuItem showSource = new CheckMenuItem("Source code");
        showSource.setAccelerator(KeyCombination.valueOf("Shortcut+U"));
        showSource.setSelected(true);
        showSource.selectedProperty().addListener((o, was, show) -> {
            if (show) {
                main.getItems().add(source);
                main.setDividerPositions(0.65);
            } else {
                main.getItems().remove(source);
            }
        });
        // JavaFX reads this only when it starts, so the showcase starts again, on the same example.
        CheckMenuItem vsync = new CheckMenuItem("Frames at the screen's rate (vsync)");
        vsync.setSelected(Launcher.vsync());
        vsync.setOnAction(e -> {
            try {
                Launcher.restart(vsync.isSelected(), selection.getSelectedItem().getText());
                Platform.exit();
            } catch (IOException ex) {
                vsync.setSelected(!vsync.isSelected());
            }
        });
        return new Menu("View", null, next, previous, new SeparatorMenuItem(), showSource, vsync);
    }

    private void show(Tab old, Tab tab) {
        if (old != null) {
            example(old).stop();
            old.setContent(null);
        }
        Example example = example(tab);
        Label description = new Label(example.description());
        description.setWrapText(true);
        description.setPadding(new Insets(10, 12, 10, 12));
        tab.setContent(new BorderPane(example.create(), description, null, null, null));
        source.show(sourceOf(example));
        for (ToggleGroup group : List.of(menuSelection, tabSelection)) {
            group.getToggles().stream()
                    .filter(toggle -> toggle.getUserData() == tab)
                    .findFirst()
                    .ifPresent(toggle -> toggle.setSelected(true));
        }
    }

    private static Example example(Tab tab) {
        return (Example) tab.getUserData();
    }

    /// The example's source file without the package line, and the helpers it uses: one button each in the source
    /// pane. The sources are bundled as resources next to the classes.
    private static List<SourceView.SourceFile> sourceOf(Example example) {
        String name = example.getClass().getSimpleName() + ".java";
        String own = source(name);
        List<SourceView.SourceFile> files = new ArrayList<>(List.of(new SourceView.SourceFile(name, own)));
        for (String helper : HELPERS) {
            if (own.contains(helper.replace(".java", ""))) {
                files.add(new SourceView.SourceFile(helper, source(helper)));
            }
        }
        return files;
    }

    /** The source pane, for tests. */
    SourceView sourceView() {
        return source;
    }

    private static String source(String file) {
        try (InputStream in = Example.class.getResourceAsStream(file)) {
            if (in == null) {
                return "Source not found: " + file;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.startsWith("package "))
                    .collect(Collectors.joining("\n"))
                    .strip();
        } catch (IOException e) {
            return "Source not readable: " + e;
        }
    }
}
