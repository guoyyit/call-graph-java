package com.github.guoyyit.callgraphjava.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * 基于本地文件系统的持久化存储，目录结构：
 * <pre>
 * dataDir/
 *   wal.log                 —— 追加型预写日志（每行一条 JSON）
 *   snapshots/
 *     snapshot-N.json.tmp   —— 写入中的临时文件（崩溃残留，恢复时删除）
 *     snapshot-N.json       —— 已提交快照，N 为版本号；仅保留最新一份
 * </pre>
 *
 * <p>崩溃安全协议：
 * <ol>
 *   <li>变更开始：{@link #appendWal(WalEntry)} 以 append+sync 落盘；</li>
 *   <li>快照写入：先写 {@code .tmp} 并 fsync，再原子 rename 为正式文件名；</li>
 *   <li>{@link #commit} 成功后截断 WAL（以 sync 清空）。</li>
 * </ol>
 * 因此：崩溃在 commit 之前 —— 旧快照完好、WAL 记录待重做变更；
 * 崩溃在 rename 之前 —— 只残留 {@code .tmp}，恢复时清理；
 * 崩溃在 rename 之后、WAL 清空前 —— 新快照已是该事务结果，{@link #recover}
 * 按事务号比对，跳过已包含在快照中的 WAL 条目。任何情况下都不会留下部分写入，
 * 也不会重复应用或跳过变更。
 */
public final class SnapshotStore {

    private final Path dataDir;
    private final Path snapshotDir;
    private final Path walFile;
    private final SnapshotCodec codec = new SnapshotCodec();
    private final ObjectMapper plainMapper = new ObjectMapper();

    public SnapshotStore(Path dataDir) {
        this.dataDir = dataDir;
        this.snapshotDir = dataDir.resolve("snapshots");
        this.walFile = dataDir.resolve("wal.log");
        try {
            Files.createDirectories(snapshotDir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create data dir " + dataDir, e);
        }
    }

    /** 启动恢复：清理临时/半成品文件，读取最新完整快照与未提交 WAL。 */
    public StoredSnapshot recover() {
        cleanupTempFiles();
        return readLatestSnapshot();
    }

    /** 读取仍需重做的 WAL 条目（按事务号升序）；已包含在快照中的条目由服务层过滤。 */
    public List<WalEntry> readWal() {
        if (!Files.exists(walFile)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(walFile, StandardCharsets.UTF_8);
            return lines.stream()
                    .filter(line -> !line.isBlank())
                    .map(this::parseWalLine)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read WAL", e);
        }
    }

    public synchronized void appendWal(WalEntry entry) {
        try {
            String line = plainMapper.writeValueAsString(entry) + System.lineSeparator();
            Files.writeString(walFile, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            force(walFile);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append WAL", e);
        }
    }

    /** 原子提交：临时文件 fsync 后 rename；成功才清空 WAL。 */
    public synchronized void commit(StoredSnapshot snapshot) {
        Path target = snapshotDir.resolve("snapshot-" + snapshot.version() + ".json");
        Path temp = snapshotDir.resolve("snapshot-" + snapshot.version() + ".json.tmp");
        try {
            String json = codec.encode(snapshot);
            Files.writeString(temp, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            force(temp);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            removeOlderSnapshots(snapshot.version());
            clearWal();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot commit snapshot " + snapshot.version(), e);
        }
    }

    public long nextTransactionId() {
        long max = readWal().stream().mapToLong(WalEntry::transactionId).max().orElse(0L);
        StoredSnapshot snapshot = readLatestSnapshot();
        if (snapshot != null) {
            max = Math.max(max, snapshot.transactionId());
        }
        return max + 1;
    }

    public Path dataDir() {
        return dataDir;
    }

    private StoredSnapshot readLatestSnapshot() {
        StoredSnapshot latest = null;
        try (Stream<Path> paths = Files.list(snapshotDir)) {
            List<Path> files = paths
                    .filter(p -> p.getFileName().toString().matches("snapshot-\\d+\\.json"))
                    .toList();
            for (Path file : files) {
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    StoredSnapshot candidate = codec.decode(json);
                    if (latest == null || candidate.version() > latest.version()) {
                        latest = candidate;
                    }
                } catch (RuntimeException decodeError) {
                    // 半成品/损坏快照：忽略，交由更高版本候选或全量重建兜底。
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list snapshots", e);
        }
        return latest;
    }

    private void cleanupTempFiles() {
        try (Stream<Path> paths = Files.list(snapshotDir)) {
            for (Path path : paths.toList()) {
                if (path.getFileName().toString().endsWith(".tmp")) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot clean temp files", e);
        }
    }

    private void removeOlderSnapshots(long committedVersion) {
        try (Stream<Path> paths = Files.list(snapshotDir)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (name.matches("snapshot-\\d+\\.json")
                        && !name.equals("snapshot-" + committedVersion + ".json")) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot remove old snapshots", e);
        }
    }

    private void clearWal() {
        try {
            Files.writeString(walFile, "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            force(walFile);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot clear WAL", e);
        }
    }

    private WalEntry parseWalLine(String line) {
        try {
            return plainMapper.readValue(line, new TypeReference<WalEntry>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("corrupt WAL line: " + line, e);
        }
    }

    private static void force(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            try (var channel = java.nio.channels.FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
        }
    }
}
