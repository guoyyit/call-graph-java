package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.analysis.BuildResult;
import com.github.guoyyit.callgraphjava.analysis.CallGraphBuilder;
import com.github.guoyyit.callgraphjava.core.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgeCaseTest {

    @TempDir
    Path repo;

    @Test
    void emptyRepositoryBuildsWithoutError() {
        BuildResult result = new CallGraphBuilder().buildFull(repo, CancellationToken.NONE);
        assertEquals(0, result.graph().methods().size());
        assertEquals(0, result.graph().edges().size());
    }

    @Test
    void externalLibraryCallsProduceNoPhantomEdges() throws Exception {
        Path file = repo.resolve("p/UsesExternal.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package p;
                import java.util.List;
                import java.util.ArrayList;
                public class UsesExternal {
                    public int run() {
                        List<String> list = new ArrayList<>();
                        list.add("x");
                        return list.size();
                    }
                }
                """);
        BuildResult result = new CallGraphBuilder().buildFull(repo, CancellationToken.NONE);
        // 仅仓库内方法入图；java.util.* 的调用不产生幻影边
        for (var edge : result.graph().edges()) {
            assertTrue(edge.callee().ownerType().startsWith("p."),
                    "unexpected external edge: " + edge);
        }
        assertTrue(result.graph().methods().stream()
                .anyMatch(m -> m.ownerType().equals("p.UsesExternal")));
    }

    @Test
    void overloadedMethodsAreBothCandidates() throws Exception {
        Path file = repo.resolve("p/Calc.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package p;
                public class Calc {
                    public int compute(int x) { return x; }
                    public int compute(String s) { return s.length(); }
                    public int caller1() { return compute(1); }
                    public int caller2() { return compute("a"); }
                }
                """);
        BuildResult result = new CallGraphBuilder().buildFull(repo, CancellationToken.NONE);
        long computeEdges = result.graph().edges().stream()
                .filter(e -> e.callee().name().equals("compute"))
                .count();
        // 两个调用点各至少匹配到同 arity 的 compute(int)
        // （String 参数是外部类型占位符，compute(String) 以 (*external*) 表示）
        assertTrue(computeEdges >= 2, "compute edges=" + computeEdges);
    }

    @Test
    void staticImportUnqualifiedCallResolves() throws Exception {
        Path maths = repo.resolve("p/Maths.java");
        Files.createDirectories(maths.getParent());
        Files.writeString(maths, """
                package p;
                public class Maths { public static int square(int x) { return x * x; } }
                """);
        Path user = repo.resolve("p/User.java");
        Files.writeString(user, """
                package p;
                import static p.Maths.square;
                public class User {
                    public int a() { return square(3); }
                }
                """);
        BuildResult result = new CallGraphBuilder().buildFull(repo, CancellationToken.NONE);
        assertTrue(result.graph().edges().stream()
                .anyMatch(e -> e.caller().name().equals("a")
                        && e.callee().ownerType().equals("p.Maths")
                        && e.callee().name().equals("square")));
    }
}
