package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.analysis.BuildResult;
import com.github.guoyyit.callgraphjava.analysis.CallGraphBuilder;
import com.github.guoyyit.callgraphjava.core.CancellationToken;
import com.github.guoyyit.callgraphjava.graph.GraphQueries;
import com.github.guoyyit.callgraphjava.graph.SccAnalyzer;
import com.github.guoyyit.callgraphjava.graph.SccResult;
import com.github.guoyyit.callgraphjava.model.CallEdge;
import com.github.guoyyit.callgraphjava.model.MethodId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CycleAndReachabilityTest {

    @TempDir
    Path repo;

    private void write(String relative, String content) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void detectsRecursiveSccAcrossPackagesAndReachability() throws Exception {
        // 跨包相互递归：alpha.A <-> beta.B
        write("alpha/A.java", """
                package alpha;
                import beta.B;
                public class A {
                    public void ping(B b) { b.pong(this); }
                    public void self() { self(); }
                }
                """);
        write("beta/B.java", """
                package beta;
                import alpha.A;
                public class B {
                    public void pong(A a) { a.ping(this); }
                }
                """);
        write("gamma/C.java", """
                package gamma;
                public class C {
                    public void standalone() { }
                }
                """);

        BuildResult result = new CallGraphBuilder().buildFull(repo, CancellationToken.NONE);
        SccResult scc = new SccAnalyzer(result.graph()).analyze(CancellationToken.NONE);
        GraphQueries queries = new GraphQueries(result.graph());

        Set<String> recursive = scc.recursiveMethods().stream()
                .map(m -> m.ownerType() + "#" + m.name()).collect(Collectors.toSet());
        assertTrue(recursive.contains("alpha.A#ping"), recursive.toString());
        assertTrue(recursive.contains("beta.B#pong"), recursive.toString());
        assertTrue(recursive.contains("alpha.A#self"), recursive.toString());
        assertFalse(recursive.contains("gamma.C#standalone"), recursive.toString());

        MethodId ping = find(result, "alpha.A", "ping");
        MethodId pong = find(result, "beta.B", "pong");
        MethodId self = find(result, "alpha.A", "self");
        MethodId standalone = find(result, "gamma.C", "standalone");
        assertTrue(queries.isReachable(ping, pong));
        assertTrue(queries.isReachable(pong, ping));
        assertTrue(queries.isReachable(self, self));
        assertFalse(queries.isReachable(ping, standalone));
        assertEquals(false, queries.findPath(ping,
                new MethodId("gamma.C", "standalone", "()")).size() > 0);

        // 跨包边：ping->pong 与 pong->ping 均跨 alpha<->beta
        Set<CallEdge> cross = queries.crossPackageEdges();
        assertTrue(cross.stream().anyMatch(e -> e.caller().equals(ping)
                && e.callee().equals(pong)), cross.toString());
        assertTrue(cross.stream().anyMatch(e -> e.caller().equals(pong)
                && e.callee().equals(ping)), cross.toString());
    }

    private static MethodId find(BuildResult result, String owner, String name) {
        return result.graph().methods().stream()
                .filter(m -> m.ownerType().equals(owner) && m.name().equals(name))
                .findFirst().orElseThrow();
    }

    @Test
    void acyclicGraphHasNoRecursiveMethods() throws Exception {
        write("p/Dag.java", """
                package p;
                public class Dag {
                    public void a() { b(); }
                    public void b() { c(); }
                    public void c() { }
                }
                """);
        BuildResult result = new CallGraphBuilder().buildFull(repo, CancellationToken.NONE);
        SccResult scc = new SccAnalyzer(result.graph()).analyze(CancellationToken.NONE);
        assertTrue(scc.recursiveMethods().isEmpty());
        assertTrue(new GraphQueries(result.graph())
                .isReachable(new MethodId("p.Dag", "a", "()"),
                        new MethodId("p.Dag", "c", "()")));
    }
}
