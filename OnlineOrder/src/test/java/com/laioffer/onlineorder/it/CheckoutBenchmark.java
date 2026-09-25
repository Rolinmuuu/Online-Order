package com.laioffer.onlineorder.it;

import com.laioffer.onlineorder.platform.OutOfStock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Service-level checkout benchmark against a real PostgreSQL (no HTTP in the loop).
 * Not a unit test: run it by hand, e.g.
 *
 * <pre>TEST_DATABASE_URL=... java -cp ... com.laioffer.onlineorder.it.CheckoutBenchmark</pre>
 *
 * Scenarios: every checkout contends for one limited item (a flash sale on one hot inventory
 * row) versus checkouts of unlimited items (no shared row). Prints throughput and latency
 * percentiles for several thread counts and checks that nothing was oversold.
 */
public class CheckoutBenchmark extends PostgresTestSupport {

    record Result(String scenario, int threads, int orders, double seconds, long p50, long p95, long p99, int soldOut) {
        @Override
        public String toString() {
            return String.format("| %-22s | %3d | %5d | %7.0f | %5.1f | %5.1f | %5.1f | %s |", scenario, threads, orders,
                    orders / seconds, p50 / 1000.0, p95 / 1000.0, p99 / 1000.0, soldOut == 0 ? "-" : soldOut + " sold out");
        }
    }

    Result run(String scenario, long menuItemId, Integer stock, int threads, int orders) throws Exception {
        return run(scenario, menuItemId, stock, threads, orders, false);
    }

    Result run(String scenario, long menuItemId, Integer stock, int threads, int orders, boolean stockFirst) throws Exception {
        freshSchema();
        setReserveFirst(stockFirst);
        if (stock != null) {
            setStock(menuItemId, stock);
        }
        List<Long> customers = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            long c = customer("b" + i + "@bench");
            addToCart(c, menuItemId, 1);
            customers.add(c);
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Long> latenciesMicros = Collections.synchronizedList(new ArrayList<>());
        int[] soldOut = {0};
        long start = System.nanoTime();
        List<Future<?>> fs = new ArrayList<>();
        for (long c : customers) {
            fs.add(pool.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    checkout.checkout(c, null, null);
                } catch (OutOfStock e) {
                    synchronized (soldOut) {
                        soldOut[0]++;
                    }
                }
                latenciesMicros.add((System.nanoTime() - t0) / 1000);
            }));
        }
        for (Future<?> f : fs) {
            f.get(5, TimeUnit.MINUTES);
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        pool.shutdown();
        if (stock != null) {
            int sold = count("SELECT COALESCE(SUM(quantity), 0) FROM order_lines WHERE menu_item_id = ?", menuItemId);
            if (sold > stock || stock(menuItemId) < 0) {
                throw new AssertionError("oversold: " + sold + " of " + stock);
            }
        }
        List<Long> l = new ArrayList<>(latenciesMicros);
        Collections.sort(l);
        return new Result(scenario, threads, orders, seconds,
                l.get(l.size() / 2), l.get((int) (l.size() * 0.95)), l.get((int) (l.size() * 0.99)), soldOut[0]);
    }

    void setReserveFirst(boolean first) {
        com.laioffer.onlineorder.ordering.CheckoutTestAccess.reserveStockFirst(checkout, first);
    }

    public static void main(String[] args) throws Exception {
        connect();
        CheckoutBenchmark b = new CheckoutBenchmark();
        int orders = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        List<Result> results = new ArrayList<>();
        b.run("warm-up", 1, null, 8, 300);
        // Interleaved rounds, so drift in the machine affects every variant alike.
        for (int round = 1; round <= rounds; round++) {
            for (int threads : new int[]{1, 8, 32}) {
                results.add(b.run("unlimited dish", 1, null, threads, orders));
                results.add(b.run("hot dish, stock LAST", 2, orders, threads, orders, false));
                results.add(b.run("hot dish, stock FIRST", 2, orders, threads, orders, true));
            }
            results.add(b.run("flash sale 500/2000", 2, orders / 4, 32, orders));
        }
        System.out.println("| scenario               | thr | orders | orders/s | p50 ms | p95 ms | p99 ms | notes |");
        System.out.println("|---|---|---|---|---|---|---|---|");
        results.forEach(System.out::println);
        disconnect();
    }
}
