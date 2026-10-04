package com.github.guoyyit.callgraphjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public void append(long revision, List<String> changedFiles) {
        begin(revision, changedFiles);
        commit(revision);
    }

    public void begin(long revision, List<String> changedFiles) {
        Map<String, Object> begin = new LinkedHashMap<>();
        begin.put("op", BEGIN);
        begin.put("revision", revision);
        begin.put("files", changedFiles);
        appendEntries(List.of(begin));
    }

    public void commit(long revision) {
        Map<String, Object> commit = new LinkedHashMap<>();
        commit.put("op", COMMIT);
        commit.put("revision", revision);
        appendEntries(List.of(commit));
    }

    private void appendEntries(List<Map<String, Object>> entries) {
        try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            for (Map<String, Object> entry : entries) {
                channel.write(StandardCharsets.UTF_8.encode(mapper.writeValueAsString(entry)
                        + System.lineSeparator()));
            }
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append journal entry", e);
        }
    }

    public void discard(long revision) {
        rewriteKeeping(entry -> ((Number) entry.get("revision")).longValue() != revision);
    }

    public void discardIncomplete() {
        Set<Long> committed = new java.util.HashSet<>();
        for (Map<?, ?> entry : readEntries()) {
            if (COMMIT.equals(entry.get("op"))) {
                committed.add(((Number) entry.get("revision")).longValue());
            }
        }
        rewriteKeeping(entry -> {
            long revision = ((Number) entry.get("revision")).longValue();
            return committed.contains(revision);
        });
    }

    private void rewriteKeeping(java.util.function.Predicate<Map<?, ?>> keep) {
        try {
            List<String> kept = new ArrayList<>();
            for (Map<?, ?> entry : readEntries()) {
                if (keep.test(entry)) {
                    kept.add(mapper.writeValueAsString(entry));
                }
            }
            Files.writeString(journal, String.join(System.lineSeparator(), kept)
                    + (kept.isEmpty() ? "" : System.lineSeparator()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot rewrite journal", e);
        }
    }

    private List<Map<?, ?>> readEntries() {
        List<Map<?, ?>> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                entries.add(mapper.readValue(line, Map.class));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read journal", e);
        }
        return entries;
    }

    public List<PendingChange> pendingChanges(long lastSnapshotRevision) {
        Map<Long, List<String>> begins = new LinkedHashMap<>();
        Set<Long> committedRevisions = new java.util.HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Map<?, ?> entry = mapper.readValue(line, Map.class);
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
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read journal", e);
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
        try (BufferedReader reader = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Map<?, ?> entry = mapper.readValue(line, Map.class);
                if (COMMIT.equals(entry.get("op"))) {
                    latest = Math.max(latest, ((Number) entry.get("revision")).longValue());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read journal", e);
        }
        return latest;
    }

    public void truncateThrough(long revision) {
        try {
            List<String> kept = new ArrayList<>();
            for (String line : Files.readAllLines(journal, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                Map<?, ?> entry = mapper.readValue(line, Map.class);
                long entryRevision = ((Number) entry.get("revision")).longValue();
                if (entryRevision > revision) {
                    kept.add(line);
                }
            }
            Files.writeString(journal, String.join(System.lineSeparator(), kept)
                    + (kept.isEmpty() ? "" : System.lineSeparator()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot truncate journal", e);
        }
    }

    public record PendingChange(long revision, List<String> files) {
    }
}
