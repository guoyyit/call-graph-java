package com.github.guoyyit.callgraphjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JournalStore {
    private static final String BEGIN = "BEGIN";
    private static final String COMMIT = "COMMIT";

    private final Path journal;
    private final ObjectMapper mapper = new ObjectMapper();

    public JournalStore(Path stateDirectory) {
        this.journal = stateDirectory.resolve("journal.log");
        try {
            Files.createDirectories(stateDirectory);
            if (!Files.exists(journal)) {
                Files.createFile(journal);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot initialize journal " + journal, e);
        }
    }

    /**
     * Writes only the BEGIN record for a change. The change is not recoverable
     * until {@link #commit(long)} has been written and fsynced.
     */
    public void begin(long revision, List<String> changedFiles) {
        Map<String, Object> begin = new LinkedHashMap<>();
        begin.put("op", BEGIN);
        begin.put("revision", revision);
        begin.put("files", changedFiles);
        appendLines(List.of(toJson(begin)));
    }

    /**
     * Writes the COMMIT record that marks a change as durable. This is the
     * commit point of the WAL protocol.
     */
    public void commit(long revision) {
        Map<String, Object> commit = new LinkedHashMap<>();
        commit.put("op", COMMIT);
        commit.put("revision", revision);
        appendLines(List.of(toJson(commit)));
    }

    /**
     * Writes BEGIN and COMMIT together. Used by tests and by callers that
     * record an already-applied change.
     */
    public void append(long revision, List<String> changedFiles) {
        Map<String, Object> begin = new LinkedHashMap<>();
        begin.put("op", BEGIN);
        begin.put("revision", revision);
        begin.put("files", changedFiles);
        Map<String, Object> commit = new LinkedHashMap<>();
        commit.put("op", COMMIT);
        commit.put("revision", revision);
        appendLines(List.of(toJson(begin), toJson(commit)));
    }

    /**
     * Removes every record belonging to the given revision. Used when a change
     * is cancelled or fails before its commit point, so a cancelled change
     * never leaves a recoverable trace.
     */
    public void discard(long revision) {
        rewriteKeeping(entry -> ((Number) entry.get("revision")).longValue() != revision);
    }

    /**
     * Drops BEGIN records that have no matching COMMIT. Such records are
     * leftovers of changes that were cancelled or interrupted by a crash and
     * must never be replayed.
     */
    public void removeUncommitted() {
        Set<Long> committed = new java.util.HashSet<>();
        for (Map<?, ?> entry : readEntries()) {
            if (COMMIT.equals(entry.get("op"))) {
                committed.add(((Number) entry.get("revision")).longValue());
            }
        }
        rewriteKeeping(entry -> !BEGIN.equals(entry.get("op"))
                || committed.contains(((Number) entry.get("revision")).longValue()));
    }

    private String toJson(Map<String, Object> entry) {
        try {
            return mapper.writeValueAsString(entry);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot encode journal entry", e);
        }
    }

    private void appendLines(List<String> lines) {
        try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            for (String line : lines) {
                channel.write(StandardCharsets.UTF_8.encode(line + System.lineSeparator()));
            }
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append journal entry", e);
        }
    }

    private List<Map<?, ?>> readEntries() {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read journal", e);
        }
        List<Map<?, ?>> entries = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            try {
                entries.add(mapper.readValue(lines.get(index), Map.class));
            } catch (IOException e) {
                if (index == lines.size() - 1) {
                    // A crash can leave a torn record at the tail of the
                    // journal; it never reached its commit point, so skip it.
                    break;
                }
                throw new UncheckedIOException("cannot read journal", e);
            }
        }
        return entries;
    }

    private void rewriteKeeping(java.util.function.Predicate<Map<?, ?>> keep) {
        List<String> kept = new ArrayList<>();
        for (Map<?, ?> entry : readEntries()) {
            if (keep.test(entry)) {
                Map<String, Object> copy = new LinkedHashMap<>();
                copy.put("op", entry.get("op"));
                copy.put("revision", ((Number) entry.get("revision")).longValue());
                if (entry.get("files") != null) {
                    copy.put("files", entry.get("files"));
                }
                kept.add(toJson(copy));
            }
        }
        writeAtomically(kept);
    }

    private void writeAtomically(List<String> lines) {
        Path pending = journal.resolveSibling(journal.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (String line : lines) {
                channel.write(StandardCharsets.UTF_8.encode(line + System.lineSeparator()));
            }
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot rewrite journal", e);
        }
        try {
            Files.move(pending, journal, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot replace journal", e);
        }
    }

    public List<PendingChange> pendingChanges(long lastSnapshotRevision) {
        Map<Long, List<String>> begins = new LinkedHashMap<>();
        Set<Long> committedRevisions = new java.util.HashSet<>();
        for (Map<?, ?> entry : readEntries()) {
            long revision = ((Number) entry.get("revision")).longValue();
            if (revision <= lastSnapshotRevision) {
                continue;
            }
            String op = (String) entry.get("op");
            if (BEGIN.equals(op)) {
                begins.put(revision, entry.get("files") == null ? List.of()
                        : (List<String>) entry.get("files"));
            } else if (COMMIT.equals(op)) {
                committedRevisions.add(revision);
            }
        }
        List<PendingChange> result = new ArrayList<>();
        for (Map.Entry<Long, List<String>> begin : begins.entrySet()) {
            if (committedRevisions.contains(begin.getKey())) {
                result.add(new PendingChange(begin.getKey(), begin.getValue()));
            }
        }
        return result;
    }

    public long latestCommittedRevision() {
        long latest = 0;
        for (Map<?, ?> entry : readEntries()) {
            if (COMMIT.equals(entry.get("op"))) {
                latest = Math.max(latest, ((Number) entry.get("revision")).longValue());
            }
        }
        return latest;
    }

    public void truncateThrough(long revision) {
        rewriteKeeping(entry -> ((Number) entry.get("revision")).longValue() > revision);
    }

    public record PendingChange(long revision, List<String> files) {
    }
}
