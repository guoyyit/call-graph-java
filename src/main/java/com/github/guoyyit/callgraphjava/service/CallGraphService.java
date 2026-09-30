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
import java.util.concurrent.locks.ReentrantLock;

public class CallGraphService {
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
            long revision = store.current().revision() + 1;
            List<String> allFiles = scanner.scan(root).stream().map(path -> relativize(root, path)).toList();
            journal.append(revision, allFiles);
            GraphSnapshot built = builder.buildFull(root, token);
            GraphSnapshot committed = new GraphSnapshot(revision, built.rawFiles(), built.files(),
                    built.types(), built.methods(), built.resolvedReceiver(), built.edges());
            persistAndCommit(committed);
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
            long revision = base.revision() + 1;
            journal.append(revision, changed);
            GraphSnapshot updated = updater.update(base, repositoryRoot, changed, token);
            GraphSnapshot committed = new GraphSnapshot(revision, updated.rawFiles(), updated.files(),
                    updated.types(), updated.methods(), updated.resolvedReceiver(), updated.edges());
            persistAndCommit(committed);
            return committed;
        } finally {
            updateLock.unlock();
        }
    }

    public void recover() {
        updateLock.lock();
        try {
            Path snapshotPath = stateDirectory.resolve("snapshot.json");
            GraphSnapshot loaded = persistence.read(snapshotPath);
            if (loaded != null) {
                store.commit(loaded);
            }
            for (JournalStore.PendingChange pending : journal.pendingChanges(store.current().revision())) {
                GraphSnapshot updated = updater.update(store.current(), repositoryRoot,
                        pending.files(), ProgressToken.neverCancelled());
                GraphSnapshot committed = new GraphSnapshot(pending.revision(), updated.rawFiles(),
                        updated.files(), updated.types(), updated.methods(),
                        updated.resolvedReceiver(), updated.edges());
                persistAndCommit(committed);
            }
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

    private void persistAndCommit(GraphSnapshot committed) {
        persistence.writeAtomically(stateDirectory.resolve("snapshot.json"), committed);
        store.commit(committed);
        journal.truncateThrough(committed.revision());
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
