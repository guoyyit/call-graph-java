package com.github.guoyyit.callgraphjava.service;

import com.github.guoyyit.callgraphjava.analysis.BuildResult;
import com.github.guoyyit.callgraphjava.analysis.CallGraphBuilder;
import com.github.guoyyit.callgraphjava.core.BuildCancelledException;
import com.github.guoyyit.callgraphjava.core.CancellationToken;
import com.github.guoyyit.callgraphjava.incremental.ChangePlanner;
import com.github.guoyyit.callgraphjava.incremental.ChangeSet;
import com.github.guoyyit.callgraphjava.incremental.FileChange;
import com.github.guoyyit.callgraphjava.model.ParsedFile;
import com.github.guoyyit.callgraphjava.store.SnapshotStore;
import com.github.guoyyit.callgraphjava.store.StoredSnapshot;
import com.github.guoyyit.callgraphjava.store.WalEntry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 默认调用图服务。
 *
 * <p>并发模型：所有更新操作在同一把 {@link ReentrantLock} 下串行（单写者），
 * 当前快照保存在 {@code volatile} 字段中，查询无需加锁即可获得完整快照引用（多读者）。
 *
 * <p>取消语义：取消在「WAL 追加」与「快照发布」之前的检查点生效。构建中途抛出
 * {@link BuildCancelledException} 时不会触碰已提交快照；若 WAL 已追加但尚未提交，
 * 其变更由 {@link #recover()} 或下次更新继续完成（不会丢失也不会重复应用）。
 */
public final class DefaultCallGraphService implements CallGraphService {

    private final Path repoRoot;
    private final SnapshotStore store;
    private final CallGraphBuilder builder = new CallGraphBuilder();
    private final ChangePlanner changePlanner = new ChangePlanner();
    private final ReentrantLock updateLock = new ReentrantLock();

    private volatile GraphSnapshot current;

    public DefaultCallGraphService(Path repoRoot, Path dataDir) {
        this.repoRoot = repoRoot;
        this.store = new SnapshotStore(dataDir);
    }

    @Override
    public long currentVersion() {
        GraphSnapshot snapshot = current;
        return snapshot == null ? -1L : snapshot.version();
    }

    @Override
    public GraphSnapshot currentSnapshot() {
        return current;
    }

    @Override
    public void recover() {
        updateLock.lock();
        try {
            StoredSnapshot stored = store.recover();
            if (stored != null) {
                current = new GraphSnapshot(stored.version(), stored.transactionId(),
                        stored.result(), stored.fileHashes());
            }
            // 重做已写 WAL 但未反映在快照中的事务；若快照事务号 >= WAL 条目，说明
            // 崩溃发生在 commit 之后、清 WAL 之前，直接跳过。
            for (WalEntry entry : store.readWal()) {
                if (current != null && entry.transactionId() <= current.transactionId()) {
                    continue;
                }
                replayWal(entry);
            }
        } finally {
            updateLock.unlock();
        }
    }

    @Override
    public GraphSnapshot buildFull(CancellationToken token) {
        updateLock.lock();
        try {
            long txId = store.nextTransactionId();
            token.throwIfCancelled();
            // 全量构建以「当前工作区全部源文件」为变更集写 WAL，崩溃后按增量重做等价。
            List<String> allPaths = new ArrayList<>(
                    changePlanner.currentHashes(repoRoot).keySet());
            store.appendWal(new WalEntry(txId, List.copyOf(allPaths), List.of()));
            BuildResult result = builder.buildFull(repoRoot, token);
            token.throwIfCancelled();
            long nextVersion = (current == null ? 0 : current.version() + 1);
            publish(nextVersion, txId, result);
            return current;
        } finally {
            updateLock.unlock();
        }
    }

    @Override
    public GraphSnapshot buildIncremental(CancellationToken token) {
        updateLock.lock();
        try {
            Map<String, String> baseline = current == null
                    ? Map.of() : current.fileHashes();
            ChangeSet changes = changePlanner.plan(repoRoot, baseline);
            if (changes.isEmpty()) {
                return current;
            }
            return applyChanges(changes, token);
        } finally {
            updateLock.unlock();
        }
    }

    @Override
    public GraphSnapshot applyChanges(ChangeSet changes, CancellationToken token) {
        updateLock.lock();
        try {
            long txId = store.nextTransactionId();
            token.throwIfCancelled();

            List<String> changedPaths = new ArrayList<>();
            List<String> deletedPaths = new ArrayList<>();
            for (Map.Entry<Path, FileChange> e : changes.changes().entrySet()) {
                if (e.getValue() == FileChange.DELETED) {
                    deletedPaths.add(e.getKey().toString());
                } else {
                    changedPaths.add(e.getKey().toString());
                }
            }
            // 先持久化意图，再执行：崩溃后据此重做，保证不漏变更。
            store.appendWal(new WalEntry(txId, List.copyOf(changedPaths),
                    List.copyOf(deletedPaths)));

            BuildResult result = rebuildFromChanges(changedPaths, deletedPaths, token);
            token.throwIfCancelled();
            long nextVersion = (current == null ? 0 : current.version() + 1);
            publish(nextVersion, txId, result);
            return current;
        } finally {
            updateLock.unlock();
        }
    }

    private void replayWal(WalEntry entry) {
        BuildResult result = rebuildFromChanges(
                entry.changedPaths(), entry.deletedPaths(), CancellationToken.NONE);
        long nextVersion = (current == null ? 0 : current.version() + 1);
        publish(nextVersion, entry.transactionId(), result);
    }

    private BuildResult rebuildFromChanges(List<String> changedRelativePaths,
                                           List<String> deletedRelativePaths,
                                           CancellationToken token) {
        Map<String, ParsedFile> previous = current == null
                ? Map.of() : current.parsedFiles();
        List<Path> pathsToParse = new ArrayList<>();
        for (String relative : changedRelativePaths) {
            pathsToParse.add(Path.of(relative));
        }
        Map<String, ParsedFile> parsedChanged =
                builder.parseFiles(repoRoot, pathsToParse, token);
        return builder.buildIncremental(repoRoot, previous, parsedChanged,
                Set.copyOf(deletedRelativePaths), token);
    }

    /** 原子提交到磁盘后再发布内存引用：任何读者都不可能观察到半更新状态。 */
    private void publish(long version, long transactionId, BuildResult result) {
        StoredSnapshot stored = new StoredSnapshot(version, transactionId, result,
                result.fileHashes(), System.currentTimeMillis());
        store.commit(stored);
        current = new GraphSnapshot(version, transactionId, result, result.fileHashes());
    }

    @Override
    public void shutdown() {
        // 当前状态全部不可变且已落盘，无需额外清理；保留方法以便未来扩展资源释放。
    }

    @Override
    public Path repoRoot() {
        return repoRoot;
    }
}
