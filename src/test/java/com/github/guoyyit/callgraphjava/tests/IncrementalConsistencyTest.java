package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.analysis.BuildResult;
import com.github.guoyyit.callgraphjava.analysis.CallGraphBuilder;
import com.github.guoyyit.callgraphjava.core.CancellationToken;
import com.github.guoyyit.callgraphjava.model.CallEdge;
import com.github.guoyyit.callgraphjava.model.ParsedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IncrementalConsistencyTest {

    @TempDir
    Path repo;

    private void write(String relative, String content) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Set<String> edgeFingerprint(BuildResult result) {
        Set<String> edges = new TreeSet<>();
        for (CallEdge edge : result.graph().edges()) {
            edges.add(edge.caller().fqn() + " -> " + edge.callee().fqn() + " [" + edge.kind() + "]");
        }
        return edges;
    }

    private BuildResult rebuildIncrementally(BuildResult previous,
                                             Map<String, ParsedFile> changed,
                                             Set<String> deleted) {
        return new CallGraphBuilder().buildIncremental(repo,
                previous.parsedFiles(), changed, deleted, CancellationToken.NONE);
    }

    private Map<String, ParsedFile> parse(String... relativePaths) {
        CallGraphBuilder builder = new CallGraphBuilder();
        return builder.parseFiles(repo,
                java.util.Arrays.stream(relativePaths).map(Path::of).toList(),
                CancellationToken.NONE);
    }

    @Test
    void modifyingOneFileYieldsSameResultAsFullRebuild() throws Exception {
        write("com/acme/Service.java", """
                package com.acme;
                public class Service {
                    public void work() { helper(); }
                    public void helper() { }
                    public void obsolete() { }
                }
                """);
        write("com/acme/Client.java", """
                package com.acme;
                public class Client {
                    public void run(Service s) { s.work(); }
                }
                """);

        BuildResult v1 = new CallGraphBuilder().buildFull(repo, CancellationToken.NONE);

        // 修改 Service：helper 重命名为 newHelper，删除 obsolete；work 改调 newHelper
        write("com/acme/Service.java", """
                package com.acme;
                public class Service {
                    public void work() { newHelper(); }
                    public void newHelper() { }
                }
                """);

        BuildResult full = new CallGraphBuilder().buildFull(repo, CancellationToken.NONE);
        BuildResult incremental = rebuildIncrementally(v1, parse("com/acme/Service.java"), Set.of());

        assertEquals(edgeFingerprint(full), edgeFingerprint(incremental));
    }

    @Test
    void deletingFileAndChangingHierarchyLeavesNoStaleEdges() throws Exception {
        write("com/acme/Shape.java", """
                package com.acme;
                public interface Shape { double area(); }
                """);
        write("com/acme/Circle.java", """
                package com.acme;
                public class Circle implements Shape {
                    public double area() { return 1; }
                }
                """);
        write("com/acme/Triangle.java", """
                package com.acme;
                public class Triangle implements Shape {
                    public double area() { return 3; }
                }
                """);
        write("com/acme/Client.java", """
                package com.acme;
                public class Client {
                    public double go(Shape s) { return s.area(); }
                }
                """);

        BuildResult v1 = new CallGraphBuilder().buildFull(repo, CancellationToken.NONE);
        Set<String> before = edgeFingerprint(v1);
        assertEquals(true, before.stream().anyMatch(e -> e.contains("Triangle#area")));

        // 删除 Triangle：增量结果不得再有指向 Triangle 的边
        Files.delete(repo.resolve("com/acme/Triangle.java"));
        BuildResult fullAfterDelete = new CallGraphBuilder().buildFull(repo, CancellationToken.NONE);
        BuildResult incrAfterDelete = rebuildIncrementally(v1, Map.of(),
                Set.of("com/acme/Triangle.java"));
        assertEquals(edgeFingerprint(fullAfterDelete), edgeFingerprint(incrAfterDelete));
        assertEquals(false, edgeFingerprint(incrAfterDelete).stream()
                .anyMatch(e -> e.contains("Triangle")));

        // 类型层次变化：Circle 不再实现 Shape -> Client.go 的虚候选应剔除 Circle
        write("com/acme/Circle.java", """
                package com.acme;
                public class Circle {
                    public double area() { return 1; }
                }
                """);
        BuildResult fullAfterChange = new CallGraphBuilder().buildFull(repo, CancellationToken.NONE);
        BuildResult incrAfterChange = rebuildIncrementally(incrAfterDelete,
                parse("com/acme/Circle.java"), Set.of());
        assertEquals(edgeFingerprint(fullAfterChange), edgeFingerprint(incrAfterChange));
        assertEquals(false, edgeFingerprint(incrAfterChange).stream()
                .anyMatch(e -> e.contains("Circle#area")));
    }
}
