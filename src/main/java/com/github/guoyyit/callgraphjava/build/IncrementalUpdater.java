package com.github.guoyyit.callgraphjava.build;

import com.github.guoyyit.callgraphjava.model.GraphSnapshot;
import com.github.guoyyit.callgraphjava.model.ParsedFile;
import com.github.guoyyit.callgraphjava.parse.SourceParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class IncrementalUpdater {
    private final SourceParser parser = new SourceParser();
    private final GraphBuilder builder = new GraphBuilder();

    public GraphSnapshot update(GraphSnapshot base, Path repositoryRoot, List<String> changedDisplayPaths,
                                ProgressToken token) {
        Map<String, ParsedFile> raw = new LinkedHashMap<>(base.rawFiles());
        for (String displayPath : changedDisplayPaths) {
            Path absolute = repositoryRoot.resolve(displayPath);
            if (Files.exists(absolute)) {
                raw.put(displayPath, parser.parse(absolute, displayPath));
            } else {
                raw.remove(displayPath);
            }
        }
        return builder.assemble(raw, token, base.revision() + 1);
    }

    public GraphSnapshot update(GraphSnapshot base, Path changedFile) {
        String display = changedFile.toString();
        Path root = changedFile.toAbsolutePath().getRoot();
        return update(base, root, List.of(display), ProgressToken.neverCancelled());
    }
}
