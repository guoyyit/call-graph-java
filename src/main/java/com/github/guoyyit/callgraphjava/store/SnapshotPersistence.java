package com.github.guoyyit.callgraphjava.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.callgraphjava.model.GraphSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class SnapshotPersistence {
    static final String PENDING_SUFFIX = ".tmp";

    private final ObjectMapper mapper;

    public SnapshotPersistence() {
        this(new ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        false));
    }

    public SnapshotPersistence(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void writeAtomically(Path target, GraphSnapshot snapshot) {
        Path parent = target.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path pending = target.resolveSibling(target.getFileName() + PENDING_SUFFIX);
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(pending,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                java.io.OutputStream output = new java.io.FilterOutputStream(
                        new java.io.BufferedOutputStream(java.nio.channels.Channels.newOutputStream(channel))) {
                    @Override
                    public void close() {
                        // do not close the shared FileChannel; flush only
                        try {
                            flush();
                        } catch (IOException ignored) {
                            // surfaced by channel.force below
                        }
                    }
                };
                mapper.writerWithDefaultPrettyPrinter().writeValue(output, snapshot);
                channel.force(true);
            }
            Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist snapshot to " + target, e);
        }
    }

    public GraphSnapshot read(Path source) {
        if (!Files.exists(source)) {
            return null;
        }
        try {
            return mapper.readValue(source.toFile(), new TypeReference<GraphSnapshot>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read snapshot from " + source, e);
        }
    }

}
