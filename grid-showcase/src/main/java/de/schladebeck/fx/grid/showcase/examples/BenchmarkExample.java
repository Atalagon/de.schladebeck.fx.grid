package de.schladebeck.fx.grid.showcase.examples;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;

public final class BenchmarkExample extends Example {

    private static final String SCROLLING = "ScrollBenchmark";
    private static final List<Benchmark> BENCHMARKS = List.of(
            new Benchmark("Scrolling 100,000,000 rows x 1,000 columns", SCROLLING,
                    List.of("--rows=100000000", "--columns=1000")),
            new Benchmark("Scrolling 1,000 rows x 100,000,000 columns", SCROLLING,
                    List.of("--rows=1000", "--columns=100000000")),
            new Benchmark("Drawing in renderers", "RendererBenchmark", List.of()));

    /** A benchmark program of the showcase module and its arguments. */
    private record Benchmark(String name, String program, List<String> arguments) {

        @Override
        public String toString() {
            return name;
        }
    }

    private Process process;

    public BenchmarkExample() {
        super("Benchmarks", "Starts a benchmark in its own Java process and window, with vsync and the 60 frames "
                + "per second cap switched off, and lists its results here. ScrollBenchmark scrolls through several "
                + "scenarios and shows the frame rate in its window; its grid has other fonts in some cells and styled "
                + "two-line headers, Plain leaves that out. RendererBenchmark paints every cell with one renderer "
                + "after another, to show what text, shapes, images and effects cost. Each takes about a minute; a "
                + "laptop on battery is slower. From a terminal in the project folder: "
                + "mvn compile javafx:run -Dapp=ScrollBenchmark, or -Dapp=RendererBenchmark");
    }

    @Override
    public Node create() {
        ChoiceBox<Benchmark> benchmark = new ChoiceBox<>(FXCollections.observableList(BENCHMARKS));
        benchmark.setValue(BENCHMARKS.getFirst());
        TextArea output = new TextArea();
        output.setEditable(false);
        output.setPromptText("Run starts the benchmark; its results appear here.");
        output.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace;");
        Button run = new Button("Run");
        CheckBox plain = new CheckBox("Plain");
        plain.disableProperty().bind(benchmark.valueProperty().map(chosen -> !chosen.program().equals(SCROLLING)));
        run.setOnAction(e -> {
            List<String> arguments = new ArrayList<>(benchmark.getValue().arguments());
            if (benchmark.getValue().program().equals(SCROLLING)) {
                arguments.add(plain.isSelected() ? "--style=plain" : "--style=styled");
            }
            run(benchmark.getValue().program(), arguments, output, run);
        });
        return new BorderPane(output, new ToolBar(benchmark, plain, run), null, null, null);
    }

    @Override
    public void stop() {
        if (process != null) {
            process.destroy();
        }
    }

    private void run(String program, List<String> arguments, TextArea output, Button run) {
        String mainClass = "de.schladebeck.fx.grid.showcase." + program;
        List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java")));
        Module module = getClass().getModule();
        if (module.isNamed()) {
            command.addAll(List.of("--enable-native-access=javafx.graphics",
                    "-p", System.getProperty("jdk.module.path"),
                    "-m", module.getName() + "/" + mainClass));
        } else { // the runnable jar: everything on the class path
            command.addAll(List.of("--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
                    mainClass));
        }
        command.addAll(arguments);
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            output.setText("Could not start the benchmark: " + e);
            return;
        }
        output.setText("Running " + program + " " + String.join(" ", arguments) + "\n\n");
        run.setDisable(true);
        Process running = process;
        Thread.ofVirtual().start(() -> {
            try (BufferedReader lines = running.inputReader()) {
                for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                    String text = line;
                    Platform.runLater(() -> output.appendText(text + "\n"));
                }
                int exit = running.waitFor();
                Platform.runLater(() -> output.appendText(exit == 0 ? "\nDone." : "\nStopped, exit code " + exit));
            } catch (IOException | InterruptedException e) {
                Platform.runLater(() -> output.appendText("\n" + e));
            }
            Platform.runLater(() -> run.setDisable(false));
        });
    }
}
