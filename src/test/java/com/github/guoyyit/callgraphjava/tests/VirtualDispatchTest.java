package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.analysis.BuildResult;
import com.github.guoyyit.callgraphjava.analysis.CallGraphBuilder;
import com.github.guoyyit.callgraphjava.core.CancellationToken;
import com.github.guoyyit.callgraphjava.model.CallEdge;
import com.github.guoyyit.callgraphjava.model.MethodId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualDispatchTest {

    @TempDir
    Path repo;

    private void write(String relative, String content) throws Exception {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Set<String> edgeTargets(BuildResult result, String callerFqnSubstring) {
        return result.graph().edges().stream()
                .filter(e -> e.caller().fqn().contains(callerFqnSubstring))
                .map(e -> e.callee().fqn())
                .collect(Collectors.toSet());
    }

    @Test
    void resolvesAllChaCandidatesAcrossInterfaceAndClassHierarchy() throws Exception {
        write("src/main/java/com/acme/Shape.java", """
                package com.acme;
                public interface Shape {
                    double area();
                }
                """);
        write("src/main/java/com/acme/AbstractShape.java", """
                package com.acme;
                public abstract class AbstractShape implements Shape {
                    public double area() { return 0; }
                }
                """);
        write("src/main/java/com/acme/Circle.java", """
                package com.acme;
                public class Circle extends AbstractShape {
                    public double area() { return 1; }
                }
                """);
        write("src/main/java/com/acme/Square.java", """
                package com.acme;
                public class Square implements Shape {
                    public double area() { return 2; }
                }
                """);
        write("src/main/java/com/acme/Client.java", """
                package com.acme;
                public class Client {
                    double total(Shape shape) {
                        return shape.area();
                    }
                }
                """);

        BuildResult result = new CallGraphBuilder()
                .buildFull(repo, CancellationToken.NONE);

        Set<String> targets = edgeTargets(result, "Client#total");
        // 接收者静态类型 Shape，仓库内可分配类型 Shape/AbstractShape/Circle/Square，
        // area() 的实现候选应全部被枚举（接口自身的抽象声明 + 各重写）。
        assertTrue(targets.contains("com.acme.Shape#area()"), targets.toString());
        assertTrue(targets.contains("com.acme.AbstractShape#area()"), targets.toString());
        assertTrue(targets.contains("com.acme.Circle#area()"), targets.toString());
        assertTrue(targets.contains("com.acme.Square#area()"), targets.toString());
        assertEquals(4, targets.size(), targets.toString());
    }

    @Test
    void resolvesDirectStaticAndConstructorCalls() throws Exception {
        write("src/main/java/com/acme/Util.java", """
                package com.acme;
                public class Util {
                    public static int twice(int x) { return x * 2; }
                    private int secret() { return 1; }
                    int callSecret() { return secret(); }
                }
                """);
        write("src/main/java/com/acme/Main.java", """
                package com.acme;
                public class Main {
                    public int run() {
                        Util util = new Util();
                        return Util.twice(1) + util.callSecret();
                    }
                }
                """);

        BuildResult result = new CallGraphBuilder()
                .buildFull(repo, CancellationToken.NONE);

        Set<String> mainTargets = edgeTargets(result, "Main#run");
        assertTrue(mainTargets.contains("com.acme.Util#twice(int)"), mainTargets.toString());
        assertTrue(mainTargets.contains("com.acme.Util#<init>()"), mainTargets.toString());
        assertTrue(mainTargets.contains("com.acme.Util#callSecret()"), mainTargets.toString());

        Set<String> secretTargets = edgeTargets(result, "Util#callSecret");
        assertTrue(secretTargets.contains("com.acme.Util#secret()"), secretTargets.toString());
    }
}
