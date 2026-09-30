package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.build.GraphBuilder;
import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.model.GraphSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LargeRepositoryTest {
    @Test
    void buildsGeneratedLargeRepository(@TempDir Path tempDir) throws IOException {
        Path repo = tempDir.resolve("large");
        Files.createDirectories(repo);
        int fileCount = 200;
        for (int index = 0; index < fileCount; index++) {
            String type = "Gen" + index;
            String callee = "Gen" + ((index + 1) % fileCount);
            String content = "public class " + type + " {\n"
                    + "    public void step(" + callee + " next) {\n"
                    + "        next.step(new " + callee + "());\n"
                    + "    }\n"
                    + "}\n";
            Files.writeString(repo.resolve(type + ".java"), content, StandardCharsets.UTF_8);
        }
        GraphSnapshot snapshot = new GraphBuilder()
                .buildFull(repo, ProgressToken.neverCancelled());
        assertTrue(snapshot.types().size() >= fileCount);
        assertTrue(snapshot.edges().size() >= fileCount);
    }
}
