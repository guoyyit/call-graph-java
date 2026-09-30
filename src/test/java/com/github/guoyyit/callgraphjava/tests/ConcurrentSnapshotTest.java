package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.core.CancellationToken;
import com.github.guoyyit.callgraphjava.incremental.ChangeSet;
import com.github.guoyyit.callgraphjava.incremental.FileChange;
import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.service.DefaultCallGraphService;
import com.github.guoyyit.callgraphjava.service.GraphSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentSnapshotTest {

    @TempDir
    Path repo;

    @TempDir
    Path data;

    private void write(String relative, String content) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private DefaultCallGraphService newService() {
        DefaultCallGraphService service = new DefaultCallGraphService(repo, data);
        service.recover();
        return service;
    }

    @Test
    void readersAlwaysSeeACompleteSnapshotDuringUpdates() throws Exception {
        write("com/acme/Counter.java", """
                package com.acme;
                public class Counter {
                    public int value() { return step1(); }
                    public int step1() { return step2(); }
                    public int step2() { return 0; }
                }
                """);
        DefaultCallGraphService service = newService();
        GraphSnapshot v1 = service.buildFull(CancellationToken.NONE);
        GraphSnapshot before = service.currentSnapshot();

        // 修改为完全不同的方法集
        write("com/acme/Counter.java", """
                package com.acme;
                public class Counter {
                    public int value() { return alpha(); }
                    public int alpha() { return 0; }
                }
                """);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger v1Seen = new AtomicInteger();
        AtomicInteger v2Seen = new AtomicInteger();
        AtomicInteger inconsistent = new AtomicInteger();

        int readers = 6;
        Future<?>[] futures = new Future[readers + 1];
        for (int i = 0; i < readers; i++) {
            futures[i] = pool.submit(() -> {
                try {
                    start.await();
                    for (int iter = 0; iter < 200; iter++) {
                        GraphSnapshot snapshot = service.currentSnapshot();
                        boolean seesStep1 = snapshot.result().graph().methods().stream()
                                .map(MethodId::name).anyMatch(n -> n.equals("step1"));
                        boolean seesStep2 = snapshot.result().graph().methods().stream()
                                .map(MethodId::name).anyMatch(n -> n.equals("step2"));
                        boolean seesAlpha = snapshot.result().graph().methods().stream()
                                .map(MethodId::name).anyMatch(n -> n.equals("alpha"));
                        if (seesStep1 != seesStep2) {
                            inconsistent.incrementAndGet();
                        }
                        if (seesStep1) {
                            v1Seen.incrementAndGet();
                        }
                        if (seesAlpha) {
                            v2Seen.incrementAndGet();
                        }
                        if (seesStep1 && seesAlpha) {
                            inconsistent.incrementAndGet();
                        }
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        futures[readers] = pool.submit(() -> {
            try {
                start.await();
                service.buildIncremental(CancellationToken.NONE);
            } catch (Exception ignored) {
            }
        });

        start.countDown();
        futures[readers].get(30, TimeUnit.SECONDS);
        // 更新完成后做一次确定性读，确保新版本被观察到
        assertTrue(service.currentSnapshot().result().graph().methods().stream()
                .map(MethodId::name).anyMatch(n -> n.equals("alpha")));
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(0, inconsistent.get(), "readers observed a half-updated snapshot");
        assertNotEquals(before.version(), service.currentVersion());

        // 旧快照引用仍然有效且自洽
        assertTrue(before.result().graph().methods().stream()
                .map(MethodId::name).anyMatch(n -> n.equals("step2")));
        service.shutdown();
    }

    @Test
    void snapshotStartsAbsent() {
        DefaultCallGraphService service = newService();
        assertEquals(-1L, service.currentVersion());
        assertNull(service.currentSnapshot());
    }

    @Test
    void explicitChangeSetAppliesIncrementally() throws Exception {
        write("com/acme/X.java", """
                package com.acme;
                public class X { public void a() { } }
                """);
        DefaultCallGraphService service = newService();
        service.buildFull(CancellationToken.NONE);

        write("com/acme/Y.java", "package com.acme; public class Y { public void b() { } }");
        ChangeSet changes = new ChangeSet(Map.of(
                Path.of("com/acme/Y.java"), FileChange.ADDED));
        GraphSnapshot updated = service.applyChanges(changes, CancellationToken.NONE);
        assertTrue(updated.result().graph().methods().stream()
                .anyMatch(m -> m.name().equals("b")));
        service.shutdown();
    }
}
