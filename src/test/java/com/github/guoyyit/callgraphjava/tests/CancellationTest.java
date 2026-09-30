package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.analysis.BuildResult;
import com.github.guoyyit.callgraphjava.analysis.CallGraphBuilder;
import com.github.guoyyit.callgraphjava.core.BuildCancelledException;
import com.github.guoyyit.callgraphjava.core.CancellationToken;
import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.service.DefaultCallGraphService;
import com.github.guoyyit.callgraphjava.service.GraphSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationTest {

    @TempDir
    Path repo;

    @TempDir
    Path data;

    private void writeManyFiles(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            String pkg = "pkg" + (i % 5);
            Path dir = repo.resolve("src/com/acme/" + pkg);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("Type" + i + ".java"), """
                    package com.acme.%s;
                    public class Type%d {
                        public void work%d() { step%d(); }
                        public void step%d() { }
                    }
                    """.formatted(pkg, i, i, i, i));
        }
    }

    @Test
    void cancelledBuilderThrowsAndLeavesNoResult() throws Exception {
        writeManyFiles(40);
        CancellationToken token = CancellationToken.NONE;
        BuildResult full = new CallGraphBuilder().buildFull(repo, token);
        assertTrue(full.graph().methods().size() > 40);

        CancellationToken cancelled = new CancellationToken();
        cancelled.cancel();
        assertThrows(BuildCancelledException.class,
                () -> new CallGraphBuilder().buildFull(repo, cancelled));
    }

    @Test
    void cancelledIncrementDoesNotDamageCommittedSnapshot() throws Exception {
        writeManyFiles(30);
        DefaultCallGraphService service = new DefaultCallGraphService(repo, data);
        service.recover();
        GraphSnapshot v1 = service.buildFull(CancellationToken.NONE);
        int methodCountBefore = v1.result().graph().methods().size();

        // 已取消令牌 + 无变更：无操作返回当前快照（取消没有机会破坏状态）
        CancellationToken preCancelled = new CancellationToken();
        preCancelled.cancel();
        assertEquals(v1, service.buildIncremental(preCancelled));
        assertEquals(v1.version(), service.currentVersion());
        assertEquals(methodCountBefore,
                service.currentSnapshot().result().graph().methods().size());

        // 有变更且取消：变更规划已完成，但令牌在构建前已取消 —— 不发布新版本
        writeExtraFiles(6, 4);
        assertThrows(BuildCancelledException.class,
                () -> service.buildIncremental(preCancelled));
        assertEquals(v1.version(), service.currentVersion());

        // 取消后用新令牌可以正常完成构建，服务状态完好
        GraphSnapshot v2 = service.buildIncremental(CancellationToken.NONE);
        assertNotNull(v2);
        assertTrue(v2.version() > v1.version());
        service.shutdown();
    }

    private void writeExtraFiles(int start, int count) throws Exception {
        for (int i = start; i < start + count; i++) {
            String pkg = "pkg" + (i % 5);
            Path dir = repo.resolve("src/com/acme/" + pkg);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("Extra" + i + ".java"), """
                    package com.acme.%s;
                    public class Extra%d {
                        public void go%d() { }
                    }
                    """.formatted(pkg, i, i));
        }
    }

    @Test
    void cancellationIsCooperativeBetweenFiles() throws Exception {
        writeManyFiles(8);
        // 在构建前取消：第一个文件检查点即中断
        CancellationToken token = new CancellationToken();
        token.cancel();
        assertThrows(BuildCancelledException.class,
                () -> new CallGraphBuilder().buildFull(repo, token));

        // 不取消时同样输入成功
        BuildResult ok = new CallGraphBuilder().buildFull(repo, CancellationToken.NONE);
        assertTrue(ok.graph().methods().stream().anyMatch(m -> m.name().startsWith("work")));
    }

    @Test
    void cancelledAfterWalAppendIsReplayedOnRecover() throws Exception {
        writeManyFiles(10);
        DefaultCallGraphService service = new DefaultCallGraphService(repo, data);
        service.recover();
        service.buildFull(CancellationToken.NONE);
        service.shutdown();

        // 直接构造「WAL 已记录但构建被取消（快照未提交）」现场，
        // 等价于取消点恰好落在 appendWal 之后、commit 之前。
        writeExtraFiles(100, 3);
        com.github.guoyyit.callgraphjava.store.SnapshotStore store =
                new com.github.guoyyit.callgraphjava.store.SnapshotStore(data);
        long txId = store.nextTransactionId();
        store.appendWal(new com.github.guoyyit.callgraphjava.store.WalEntry(
                txId, java.util.List.of(
                        "src/com/acme/pkg0/Extra100.java",
                        "src/com/acme/pkg1/Extra101.java",
                        "src/com/acme/pkg2/Extra102.java"),
                java.util.List.of()));

        DefaultCallGraphService restarted = new DefaultCallGraphService(repo, data);
        restarted.recover();
        GraphSnapshot snapshot = restarted.currentSnapshot();
        org.junit.jupiter.api.Assertions.assertTrue(
                snapshot.result().graph().methods().stream()
                        .anyMatch(m -> m.name().equals("go100")));
        restarted.shutdown();
    }
}
