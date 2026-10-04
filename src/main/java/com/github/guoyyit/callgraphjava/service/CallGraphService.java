package com.github.guoyyit.callgraphjava.service;

import com.github.guoyyit.callgraphjava.build.GraphBuilder;
import com.github.guoyyit.callgraphjava.build.IncrementalUpdater;
import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.model.GraphSnapshot;
import com.github.guoyyit.callgraphjava.model.ParsedFile;
import com.github.guoyyit.callgraphjava.parse.SourceParser;
import com.github.guoyyit.callgraphjava.parse.SourceScanner;
import com.github.guoyyit.callgraphjava.store.GraphStore;
import com.github.guoyyit.callgraphjava.store.JournalStore;
import com.github.guoyyit.callgraphjava.store.SnapshotPersistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.ReentrantLock;

public class CallGraphService {
    private static final String SNAPSHOT_FILE = "snapshot.json";

    private final Path repositoryRoot;
    private final Path stateDirectory;
    private final GraphStore store;
    private final GraphBuilder builder = new GraphBuilder();
    private final IncrementalUpdater updater = new IncrementalUpdater();
    private final SourceScanner scanner = new SourceScanner();
    private final SourceParser parser = new SourceParser();
    private final SnapshotPersistence persistence;
    private final JournalStore journal;
    private final ReentrantLock updateLock = new ReentrantLock();
    private long persistedRevisionFloor = -1;

    public CallGraphService(Path repositoryRoot, Path stateDirectory, GraphStore store,
                            SnapshotPersistence persistence, JournalStore journal) {
        this.repositoryRoot = repositoryRoot;
        this.stateDirectory = stateDirectory;
        this.store = store;
        this.persistence = persistence;
        this.journal = journal;
    }

    public CallGraphService(Path repositoryRoot, Path stateDirectory) {
        this(repositoryRoot, stateDirectory, new GraphStore(), new SnapshotPersistence(),
                new JournalStore(stateDirectory));
    }

    public GraphSnapshot snapshot() {
        return store.current();
    }

    public Path repositoryRoot() {
        return repositoryRoot;
    }

    public void build(Path root, ProgressToken token) {
        updateLock.lock();
        try {
            long revision = nextRevision();
            List<String> allFiles = scanner.scan(root).stream().map(path -> relativize(root, path)).toList();
            journal.begin(revision, allFiles);
            try {
                GraphSnapshot built = builder.buildFull(root, token);
                GraphSnapshot committed = new GraphSnapshot(revision, built.rawFiles(), built.files(),
                        built.types(), built.methods(), built.resolvedReceiver(), built.edges());
                persistAndCommit(committed, token);
            } catch (RuntimeException | Error failure) {
                discardUncommitted(revision);
                throw failure;
            }
        } finally {
            updateLock.unlock();
        }
    }

    public GraphSnapshot update(ProgressToken token) {
        updateLock.lock();
        try {
            GraphSnapshot base = store.current();
            List<String> changed = detectChanges(base);
            if (changed.isEmpty()) {
                return base;
            }
            long revision = nextRevision();
            journal.begin(revision, changed);
            try {
                GraphSnapshot updated = updater.update(base, repositoryRoot, changed, token);
                GraphSnapshot committed = new GraphSnapshot(revision, updated.rawFiles(), updated.files(),
                        updated.types(), updated.methods(), updated.resolvedReceiver(), updated.edges());
                persistAndCommit(committed, token);
                return committed;
            } catch (RuntimeException | Error failure) {
                discardUncommitted(revision);
                throw failure;
            }
        } finally {
            updateLock.unlock();
        }
    }

    public void recover() {
        updateLock.lock();
        try {
            Path snapshotPath = stateDirectory.resolve(SNAPSHOT_FILE);
            persistence.deletePending(snapshotPath);
            GraphSnapshot loaded = persistence.read(snapshotPath);
            if (loaded != null) {
                store.commitIfNewer(loaded);
                persistedRevisionFloor = Math.max(persistedRevisionFloor, loaded.revision());
            }
            for (JournalStore.PendingChange pending : journal.pendingChanges(store.current().revision())) {
                GraphSnapshot updated = updater.update(store.current(), repositoryRoot,
                        pending.files(), ProgressToken.neverCancelled());
                GraphSnapshot committed = new GraphSnapshot(pending.revision(), updated.rawFiles(),
                        updated.files(), updated.types(), updated.methods(),
                        updated.resolvedReceiver(), updated.edges());
                persistence.writeAtomically(snapshotPath, committed);
                store.commitIfNewer(committed);
                persistedRevisionFloor = Math.max(persistedRevisionFloor, committed.revision());
                journal.truncateThrough(committed.revision());
            }
            journal.removeUncommitted();
        } finally {
            updateLock.unlock();
        }
    }

    public List<String> detectChanges(GraphSnapshot base) {
        List<String> changed = new ArrayList<>();
        for (Path source : scanner.scan(repositoryRoot)) {
            String display = relativize(repositoryRoot, source);
            ParsedFile existing = base.rawFiles().get(display);
            String currentHash = hash(source);
            if (existing == null || !existing.contentHash().equals(currentHash)) {
                changed.add(display);
            }
        }
        for (String tracked : base.rawFiles().keySet()) {
            if (!Files.exists(repositoryRoot.resolve(tracked))) {
                changed.add(tracked);
            }
        }
        return changed;
    }

    private void persistAndCommit(GraphSnapshot committed, ProgressToken token) {
        beforeCommit(token);
        persistence.writeAtomically(stateDirectory.resolve(SNAPSHOT_FILE), committed);
        journal.commit(committed.revision());
        store.commit(committed);
        persistedRevisionFloor = Math.max(persistedRevisionFloor, committed.revision());
        journal.truncateThrough(committed.revision());
    }

    /**
     * Last cancellation checkpoint before a change becomes durable. A change
     * cancelled at or before this point leaves no recoverable record; a
     * cancellation that arrives after the commit has completed does not roll
     * the committed state back.
     */
    protected void beforeCommit(ProgressToken token) {
        if (token != null && token.isCancelled()) {
            throw new CancellationException("call graph change cancelled before commit");
        }
    }

    private void discardUncommitted(long revision) {
        try {
            journal.discard(revision);
        } finally {
            persistence.deletePending(stateDirectory.resolve(SNAPSHOT_FILE));
        }
    }

    /**
     * Revisions are strictly monotonic across commits and restarts: the next
     * revision never falls below anything already visible in memory, in the
     * persisted snapshot, or in the journal's committed records.
     */
    private long nextRevision() {
        return Math.max(store.current().revision(),
                Math.max(journal.latestCommittedRevision(), persistedRevision())) + 1;
    }

    private long persistedRevision() {
        if (persistedRevisionFloor < 0) {
            GraphSnapshot persisted = persistence.read(stateDirectory.resolve(SNAPSHOT_FILE));
            persistedRevisionFloor = persisted == null ? 0 : persisted.revision();
        }
        return persistedRevisionFloor;
    }

    private String relativize(Path root, Path source) {
        try {
            return root.toAbsolutePath().relativize(source.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            return source.toString();
        }
    }

    private String hash(Path file) {
        try {
            byte[] content = Files.readAllBytes(file);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
