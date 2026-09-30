package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.build.GraphBuilder;
import com.github.guoyyit.callgraphjava.build.IncrementalUpdater;
import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.model.GraphSnapshot;
import com.github.guoyyit.callgraphjava.store.GraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentQueryTest {
    @Test
    @Timeout(30)
    void queriesObserveOnlyCompleteSnapshots() throws Exception {
        Path repo = Path.of("src/test/resources/fixtures/shapes");
        GraphSnapshot initial = new GraphBuilder()
                .buildFull(repo, ProgressToken.neverCancelled());
        GraphStore store = new GraphStore();
        store.commit(initial);
        IncrementalUpdater updater = new IncrementalUpdater();

        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean broken = new AtomicBoolean(false);
        AtomicInteger observed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(6);

        for (int worker = 0; worker < 4; worker++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        GraphSnapshot snapshot = store.current();
                        int declared = snapshot.edges().size();
                        long distinct = snapshot.edges().stream().distinct().count();
                        if (declared != distinct || snapshot.methods().size() < 5) {
                            broken.set(true);
                        }
                        observed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                GraphSnapshot base = initial;
                for (int revision = 0; revision < 50 && !stop.get(); revision++) {
                    base = updater.update(base, repo, java.util.List.of(),
                            ProgressToken.neverCancelled());
                    store.commit(new GraphSnapshot(base.revision() + revision + 1, base.rawFiles(),
                            base.files(), base.types(), base.methods(), base.resolvedReceiver(),
                            base.edges()));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        start.countDown();
        Thread.sleep(1000);
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(observed.get() > 0, "queries should execute concurrently with updates");
        assertTrue(!broken.get(), "no query may observe a partial snapshot");
    }
}
