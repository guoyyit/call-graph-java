package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.core.CancellationToken;
import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.service.DefaultCallGraphService;
import com.github.guoyyit.callgraphjava.service.GraphSnapshot;
import com.github.guoyyit.callgraphjava.store.SnapshotStore;
import com.github.guoyyit.callgraphjava.store.StoredSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashRecoveryTest {

    @TempDir
    Path repo;

    @TempDir
    Path data;

    private void write(String relative, String content) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private boolean hasMethod(GraphSnapshot snapshot, String owner, String name) {
        return snapshot.result().graph().methods().stream()
                .anyMatch(m -> m.ownerType().equals(owner) && m.name().equals(name));
    }

    @Test
    void recoversLatestCommittedSnapshotOnRestart() throws Exception {
        write("com/acme/A.java", """
                package com.acme;
                public class A {
                    public void one() { }
                    public void two() { one(); }
                }
                """);

        DefaultCallGraphService first = new DefaultCallGraphService(repo, data);
        first.recover();
        GraphSnapshot committed = first.buildFull(CancellationToken.NONE);
        long version = committed.version();
        first.shutdown();

        // 模拟全新进程：只知道 repo/data 两个目录
        DefaultCallGraphService second = new DefaultCallGraphService(repo, data);
        second.recover();
        assertEquals(version, second.currentVersion());
        assertNotNull(second.currentSnapshot());
        assertTrue(hasMethod(second.currentSnapshot(), "com.acme.A", "two"));

        // 恢复后继续增量，版本号严格递增
        write("com/acme/B.java", "package com.acme; public class B { public void three() { } }");
        GraphSnapshot next = second.buildIncremental(CancellationToken.NONE);
        assertTrue(next.version() > version);
        assertTrue(hasMethod(next, "com.acme.B", "three"));
        second.shutdown();
    }

    @Test
    void replaysPendingWalExactlyOnceAfterCrashBeforeCommit() throws Exception {
        write("com/acme/A.java",
                "package com.acme; public class A { public void one() { } }");

        DefaultCallGraphService first = new DefaultCallGraphService(repo, data);
        first.recover();
        first.buildFull(CancellationToken.NONE);
        first.shutdown();

        // 写入新文件，并直接在存储层制造「WAL 已追加、快照未提交」的崩溃现场
        write("com/acme/B.java", "package com.acme; public class B { public void two() { } }");
        SnapshotStore store = new SnapshotStore(data);
        store.appendWal(new com.github.guoyyit.callgraphjava.store.WalEntry(
                store.nextTransactionId(), List.of("com/acme/B.java"), List.of()));

        // 重启恢复：WAL 应被重做，B.two 恰好出现一次
        DefaultCallGraphService second = new DefaultCallGraphService(repo, data);
        second.recover();
        assertTrue(hasMethod(second.currentSnapshot(), "com.acme.B", "two"),
                "pending WAL change must be replayed");
        long versionAfterRecover = second.currentVersion();
        second.shutdown();

        // 再次重启：WAL 已在提交后清空，不得重复应用（版本不再增长）
        DefaultCallGraphService third = new DefaultCallGraphService(repo, data);
        third.recover();
        assertEquals(versionAfterRecover, third.currentVersion());
        assertTrue(hasMethod(third.currentSnapshot(), "com.acme.B", "two"));
        third.shutdown();
    }

    @Test
    void toleratesLeftoverTempAndCorruptSnapshotFiles() throws Exception {
        write("com/acme/A.java",
                "package com.acme; public class A { public void one() { } }");
        DefaultCallGraphService service = new DefaultCallGraphService(repo, data);
        service.recover();
        service.buildFull(CancellationToken.NONE);
        service.shutdown();

        Path snapshots = data.resolve("snapshots");
        // 崩溃残留的临时文件与一个损坏快照：恢复应忽略并正常工作
        Files.writeString(snapshots.resolve("snapshot-99.json.tmp"), "half-written{");
        Files.writeString(snapshots.resolve("snapshot-100.json"), "{ not valid json");

        DefaultCallGraphService restarted = new DefaultCallGraphService(repo, data);
        restarted.recover();
        assertNotNull(restarted.currentSnapshot());
        assertTrue(hasMethod(restarted.currentSnapshot(), "com.acme.A", "one"));
        restarted.shutdown();
    }

    @Test
    void persistedSnapshotRoundTripsGraphAndHashes() throws Exception {
        write("com/acme/A.java", """
                package com.acme;
                public class A {
                    public void caller() { callee(); }
                    public void callee() { }
                }
                """);
        DefaultCallGraphService service = new DefaultCallGraphService(repo, data);
        service.recover();
        GraphSnapshot v = service.buildFull(CancellationToken.NONE);
        service.shutdown();

        SnapshotStore store = new SnapshotStore(data);
        StoredSnapshot stored = store.recover();
        assertNotNull(stored);
        long edgeCount = stored.result().graph().edges().size();
        assertTrue(edgeCount >= 1);
        assertTrue(stored.fileHashes().containsKey("com/acme/A.java"));

        // 恢复后调用边依然可查
        DefaultCallGraphService second = new DefaultCallGraphService(repo, data);
        second.recover();
        MethodId caller = new MethodId("com.acme.A", "caller", "()");
        assertTrue(second.currentSnapshot().queries().callers(
                new MethodId("com.acme.A", "callee", "()")).contains(caller));
        second.shutdown();
    }

    @Test
    void replaysMultiplePendingWalEntriesInOrder() throws Exception {
        write("com/acme/A.java",
                "package com.acme; public class A { public void one() { } }");
        DefaultCallGraphService first = new DefaultCallGraphService(repo, data);
        first.recover();
        first.buildFull(CancellationToken.NONE);
        first.shutdown();

        // 直接在存储层写入两条未提交 WAL（模拟连续两次崩溃）
        write("com/acme/B.java",
                "package com.acme; public class B { public void two() { } }");
        write("com/acme/C.java",
                "package com.acme; public class C { public void three() { } }");
        SnapshotStore store = new SnapshotStore(data);
        long tx1 = store.nextTransactionId();
        store.appendWal(new com.github.guoyyit.callgraphjava.store.WalEntry(
                tx1, List.of("com/acme/B.java"), List.of()));
        store.appendWal(new com.github.guoyyit.callgraphjava.store.WalEntry(
                tx1 + 1, List.of("com/acme/C.java"), List.of()));

        DefaultCallGraphService second = new DefaultCallGraphService(repo, data);
        second.recover();
        GraphSnapshot snapshot = second.currentSnapshot();
        assertTrue(hasMethod(snapshot, "com.acme.B", "two"));
        assertTrue(hasMethod(snapshot, "com.acme.C", "three"));
        // 两笔事务各产生一个新版本
        assertEquals(2L, snapshot.version());

        // 再重启：WAL 已清空，版本不变，不重复应用
        second.shutdown();
        DefaultCallGraphService third = new DefaultCallGraphService(repo, data);
        third.recover();
        assertEquals(2L, third.currentVersion());
        third.shutdown();
    }
}
