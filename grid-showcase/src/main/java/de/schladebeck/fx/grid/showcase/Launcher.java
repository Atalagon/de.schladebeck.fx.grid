package de.schladebeck.fx.grid.showcase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/// Main class of the runnable jar and of `mvn javafx:run`. In the jar JavaFX is on the class path, and the Java
/// launcher refuses a main class that extends `Application` there, so this class starts it. It also sets what
/// JavaFX reads only when it starts: with `--no-vsync` frames are no longer held back to the screen's refresh rate,
/// so the performance examples show how many frames per second the grid can paint.
public final class Launcher {

    static final String NO_VSYNC = "--no-vsync";

    private Launcher() {
    }

    public static void main(String[] args) {
        if (List.of(args).contains(NO_VSYNC)) {
            System.setProperty("prism.vsync", "false");
            System.setProperty("javafx.animation.fullspeed", "true");
        }
        Showcase.main(args);
    }

    /** Whether frames are held back to the screen's refresh rate, as they are by default. */
    static boolean vsync() {
        return !"false".equals(System.getProperty("prism.vsync"));
    }

    /** Starts the showcase again in a new process, with or without vsync, showing the given example. */
    static void restart(boolean vsync, String example) throws IOException {
        List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java")));
        Module module = Launcher.class.getModule();
        if (module.isNamed()) {
            String modules = System.getProperty("jdk.module.path");
            command.addAll(List.of("--enable-native-access=javafx.graphics", "-p", modules,
                    "-m", module.getName() + "/" + Launcher.class.getName()));
        } else { // the runnable jar: everything on the class path
            command.addAll(List.of("--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
                    Launcher.class.getName()));
        }
        command.add("--example=" + example);
        if (!vsync) {
            command.add(NO_VSYNC);
        }
        new ProcessBuilder(command).inheritIO().start();
    }
}
