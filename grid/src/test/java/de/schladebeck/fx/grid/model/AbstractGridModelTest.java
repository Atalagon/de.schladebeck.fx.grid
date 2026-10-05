package de.schladebeck.fx.grid.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.schladebeck.fx.grid.Fx;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AbstractGridModelTest {

    /** Makes {@code applyChange} callable from the tests. */
    private static final class Model extends AbstractGridModel {

        void change(Runnable change) {
            applyChange(change);
        }

        @Override
        public int getRowCount() {
            return 0;
        }

        @Override
        public int getColumnCount() {
            return 0;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return null;
        }
    }

    @BeforeAll
    static void startToolkit() {
        Fx.start();
    }

    @Test
    void aChangeOnTheFxThreadRunsBeforeTheQueuedOnes() throws Exception {
        Model model = new Model();
        List<Integer> log = new ArrayList<>();

        Fx.call(() -> {
            // The FX thread is busy here, so the other thread's changes stay queued.
            Thread other = Thread.ofPlatform().start(() -> {
                for (int i = 0; i < 100; i++) {
                    int number = i;
                    model.change(() -> log.add(number));
                }
            });
            other.join();
            model.change(() -> log.add(-1));
            return null;
        });

        List<Integer> expected = new ArrayList<>(List.of(-1));
        expected.addAll(IntStream.range(0, 100).boxed().toList());
        assertEquals(expected, Fx.call(() -> List.copyOf(log)), "row numbers from the screen stay right");
    }

    @Test
    void aFasterProducerDoesNotBlockTheFxThread() throws Exception {
        Model model = new Model();
        AtomicLong queued = new AtomicLong();
        AtomicLong applied = new AtomicLong();
        AtomicBoolean inOrder = new AtomicBoolean(true);
        AtomicBoolean stop = new AtomicBoolean();
        // Keeps 10,000 changes of 10 microseconds each waiting, so the queue never runs empty while the test looks.
        Thread producer = Thread.ofPlatform().start(() -> {
            while (!stop.get()) {
                long number = queued.get();
                model.change(() -> {
                    if (applied.getAndIncrement() != number) {
                        inOrder.set(false);
                    }
                    long end = System.nanoTime() + 10_000;
                    while (System.nanoTime() < end) {
                        Thread.onSpinWait();
                    }
                });
                queued.incrementAndGet();
                while (queued.get() - applied.get() > 10_000 && !stop.get()) {
                    Thread.onSpinWait();
                }
            }
        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (applied.get() == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            CountDownLatch heartbeat = new CountDownLatch(1);
            Platform.runLater(heartbeat::countDown);
            assertTrue(heartbeat.await(5, TimeUnit.SECONDS), "other work runs between batches of queued changes");

            assertTrue(Fx.call(() -> {
                long appliedBefore = applied.get();
                boolean[] ranRightAway = new boolean[1];
                model.change(() -> ranRightAway[0] = applied.get() == appliedBefore);
                return ranRightAway[0];
            }), "a change on the FX thread runs right away, before changes still queued from the producer");
        } finally {
            stop.set(true);
            producer.join();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (applied.get() < queued.get() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(queued.get(), applied.get());
        assertTrue(inOrder.get());
    }

    @Test
    void aFailingChangeIsReportedAndTheOthersStillRun() throws Exception {
        Model model = new Model();
        List<String> log = new CopyOnWriteArrayList<>();
        List<Throwable> reported = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler original = Fx.call(() -> {
            Thread fx = Thread.currentThread();
            Thread.UncaughtExceptionHandler handler = fx.getUncaughtExceptionHandler();
            fx.setUncaughtExceptionHandler((thread, e) -> reported.add(e));
            return handler;
        });
        try {
            // run by a scheduled batch
            CountDownLatch thirdRan = new CountDownLatch(1);
            model.change(() -> log.add("first"));
            model.change(() -> {
                throw new IllegalStateException("broken");
            });
            model.change(() -> {
                log.add("third");
                thirdRan.countDown();
            });
            assertTrue(thirdRan.await(5, TimeUnit.SECONDS), "the change after the failing one ran");

            // queued while a change on the FX thread runs first; the queued failure must not reach it
            Fx.call(() -> {
                Thread other = Thread.ofPlatform().start(() -> {
                    model.change(() -> {
                        throw new IllegalStateException("broken again");
                    });
                    model.change(() -> log.add("fifth"));
                });
                other.join();
                model.change(() -> log.add("on the FX thread"));
                return null;
            });
        } finally {
            Fx.run(() -> Thread.currentThread().setUncaughtExceptionHandler(original));
        }

        assertEquals(List.of("first", "third", "on the FX thread", "fifth"), log);
        assertEquals(List.of("broken", "broken again"), reported.stream().map(Throwable::getMessage).toList());
    }

    /// Before the toolkit starts there is no JavaFX thread to queue for. This JVM has started it already, so
    /// [BeforeLaunch] runs in a new one, with everything on the class path like a plain application.
    @Test
    void changesBeforeTheToolkitStartsRunRightAway(@TempDir Path dir) throws Exception {
        Path output = dir.resolve("output.txt");
        String path = System.getProperty("jdk.module.path", "") + File.pathSeparator
                + System.getProperty("java.class.path");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", path, BeforeLaunch.class.getName())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();

        boolean exited = process.waitFor(60, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        String text = new String(Files.readAllBytes(output));
        assertTrue(exited, "still running after 60 s:\n" + text);
        assertEquals(0, process.exitValue(), text);
    }

    /** A record on the class path, read by {@link ListGridModel#ofRecords(Class)}. */
    public record Row(String name) {
    }

    /** Fills a model in {@code main} before the toolkit starts, then changes it once the toolkit runs. */
    public static final class BeforeLaunch {

        public static void main(String[] args) {
            int status = 1;
            try {
                ListGridModel<Row> rows = ListGridModel.ofRecords(Row.class, List.of(new Row("a")));
                rows.add(new Row("b"));
                check(rows.getRowCount() == 2 && "b".equals(rows.getValueAt(1, 0)), "changes before the start");

                Platform.startup(() -> {
                });
                rows.add(new Row("c"));
                FutureTask<Integer> count = new FutureTask<>(() -> {
                    rows.add(new Row("d"));
                    return rows.getRowCount();
                });
                Platform.runLater(count);
                check(count.get(10, TimeUnit.SECONDS) == 4, "changes after the start");
                status = 0;
            } catch (Throwable e) {
                e.printStackTrace();
            }
            System.exit(status); // the JavaFX thread would keep this JVM running
        }

        private static void check(boolean condition, String what) {
            if (!condition) {
                throw new AssertionError(what);
            }
        }
    }
}
