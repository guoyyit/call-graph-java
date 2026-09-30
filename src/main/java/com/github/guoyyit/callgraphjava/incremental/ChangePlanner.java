package com.github.guoyyit.callgraphjava.incremental;

import com.github.guoyyit.callgraphjava.parser.SourceScanner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通过「当前工作区文件 SHA-256」与「最近一次提交的文件哈希表」比对得出变更集。
 * 哈希在构建产物中持久化，因此崩溃重启后变更检测仍然精确，不依赖文件 mtime。
 */
public final class ChangePlanner {

    private final SourceScanner scanner = new SourceScanner();

    public ChangeSet plan(Path repoRoot, Map<String, String> committedHashes) {
        Map<Path, FileChange> changes = new LinkedHashMap<>();
        Map<String, String> currentHashes = currentHashes(repoRoot);

        for (Map.Entry<String, String> e : currentHashes.entrySet()) {
            String relative = e.getKey();
            String committed = committedHashes.get(relative);
            if (committed == null) {
                changes.put(Path.of(relative), FileChange.ADDED);
            } else if (!committed.equals(e.getValue())) {
                changes.put(Path.of(relative), FileChange.MODIFIED);
            }
        }
        for (String relative : committedHashes.keySet()) {
            if (!currentHashes.containsKey(relative)) {
                changes.put(Path.of(relative), FileChange.DELETED);
            }
        }
        return new ChangeSet(changes);
    }

    public Map<String, String> currentHashes(Path repoRoot) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Path relative : scanner.scan(repoRoot)) {
            try {
                byte[] content = Files.readAllBytes(repoRoot.resolve(relative));
                hashes.put(relative.toString(), sha256(content));
            } catch (IOException e) {
                throw new UncheckedIOException("failed to hash " + relative, e);
            }
        }
        return hashes;
    }

    private static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
