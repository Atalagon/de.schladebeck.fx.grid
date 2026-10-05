package de.schladebeck.fx.grid.showcase.examples;

import de.schladebeck.fx.grid.Grid;
import de.schladebeck.fx.grid.model.ListGridModel;
import de.schladebeck.fx.grid.render.CellRenderers;
import de.schladebeck.fx.grid.render.CellStyle;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.paint.Color;

public final class LiveDataExample extends Example {

    public record Quote(String symbol, double price, double change, long volume, double[] trend) {
    }

    public record Trade(LocalTime time, String symbol, double price, int size) {
    }

    private static final int SYMBOLS = 2_000;
    private static final int TREND = 30;
    private static final int MAX_TRADES = 20_000;
    private static final CellStyle UP = CellStyle.DEFAULT.withTextFill(Color.web("#4ade80"));
    private static final CellStyle DOWN = CellStyle.DEFAULT.withTextFill(Color.web("#f87171"));

    private ScheduledExecutorService feed;

    public LiveDataExample() {
        super("Live data", "A background thread changes 5,000 quotes per second and inserts 100 trades per second at "
                + "the top. ListGridModel accepts changes from any thread and applies them on the JavaFX thread in "
                + "batches; updates of rows out of view cost nothing. Scroll down a little and select a trade: new "
                + "trades arrive above without moving what you look at, and the selection stays on its trade. The dark "
                + "look comes from styles.css.");
    }

    @Override
    public Node create() {
        ListGridModel<Quote> quotes = ListGridModel.ofRecords(Quote.class);
        ListGridModel<Trade> trades = ListGridModel.ofRecords(Trade.class);

        // The feed thread keeps its own state; the models are only written from there, never read.
        String[] symbols = new String[SYMBOLS];
        double[] prices = new double[SYMBOLS];
        double[][] trends = new double[SYMBOLS][TREND];
        List<Quote> initial = new ArrayList<>();
        for (int i = 0; i < SYMBOLS; i++) {
            symbols[i] = "" + (char) ('A' + i % 26) + (char) ('A' + i / 26 % 26) + (char) ('A' + i / 676);
            prices[i] = 100;
            Arrays.fill(trends[i], 100);
            initial.add(new Quote(symbols[i], 100, 0, 0, trends[i].clone()));
        }
        quotes.setAll(initial);

        Grid quoteGrid = new Grid(quotes);
        quoteGrid.getStyleClass().add("dark"); // styles.css
        quoteGrid.getColumn("Price").setRenderer(CellRenderers.number("#,##0.00"));
        quoteGrid.getColumn("Change").setRenderer(CellRenderers.number("+0.00;-0.00"));
        quoteGrid.getColumn("Volume").setRenderer(CellRenderers.dataBar(0, 1_000_000, Color.web("#3b82f6")));
        quoteGrid.getColumn("Trend").setRenderer(CellRenderers.sparkline(Color.web("#60a5fa")));
        int changeColumn = quotes.findColumn("Change");
        quoteGrid.setCellStyleProvider((row, column, value) ->
                column == changeColumn && value instanceof Double change ? (change >= 0 ? UP : DOWN) : null);
        Grid tradeGrid = new Grid(trades);
        tradeGrid.getStyleClass().add("dark");
        tradeGrid.getColumn("Time").setRenderer(
                CellRenderers.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS").toFormat()));
        tradeGrid.getColumn("Price").setRenderer(CellRenderers.number("#,##0.00"));

        Random random = new Random();
        int[] tradeCount = {0};
        feed = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("feed").factory());
        feed.scheduleAtFixedRate(() -> {
            for (int n = 0; n < 50; n++) {
                int i = random.nextInt(SYMBOLS);
                double change = (random.nextDouble() - 0.5) * 2;
                prices[i] = Math.max(1, prices[i] + change);
                System.arraycopy(trends[i], 1, trends[i], 0, TREND - 1); // the last prices, the newest last
                trends[i][TREND - 1] = prices[i];
                quotes.set(i, new Quote(symbols[i], prices[i], change, random.nextInt(1_000_000),
                        trends[i].clone()));
            }
            int i = random.nextInt(SYMBOLS);
            trades.add(0, new Trade(LocalTime.now(), symbols[i], prices[i], 1 + random.nextInt(500)));
            if (++tradeCount[0] > MAX_TRADES) {
                trades.remove(MAX_TRADES);
                tradeCount[0]--;
            }
        }, 0, 10, TimeUnit.MILLISECONDS);

        SplitPane split = new SplitPane(quoteGrid, tradeGrid);
        split.getStyleClass().addAll("cards", "dark");
        return split;
    }

    @Override
    public void stop() {
        if (feed != null) {
            feed.shutdownNow();
        }
    }
}
