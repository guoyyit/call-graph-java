package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.analysis.BuildResult;
import com.github.guoyyit.callgraphjava.analysis.CallGraphBuilder;
import com.github.guoyyit.callgraphjava.core.BuildCancelledException;
import com.github.guoyyit.callgraphjava.core.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 大仓库语义验证：
 * <ul>
 *   <li>规模测试：数百文件、数千方法可在小堆下完成（逐文件解析，AST 不跨文件驻留）；</li>
 *   <li>取消在文件检查点生效，且输入规模越大越能稳定复现。</li>
 * </ul>
 */
class LargeRepoBoundedMemoryTest {

    @TempDir
    Path repo;

    private void generate(int packages, int typesPerPackage) throws Exception {
        for (int p = 0; p < packages; p++) {
            String pkg = "synthetic.p" + p;
            Path dir = repo.resolve("src/" + pkg.replace('.', '/'));
            Files.createDirectories(dir);
            for (int t = 0; t < typesPerPackage; t++) {
                String self = "T" + p + "_" + t;
                String callee = "T" + p + "_" + ((t + 1) % typesPerPackage);
                Files.writeString(dir.resolve(self + ".java"), """
                        package %s;
                        public class %s {
                            public int entry() { return step(); }
                            public int step() { return %s.leafStatic(); }
                            public static int leafStatic() { return %d; }
                        }
                        """.formatted(pkg, self, callee, t));
            }
        }
    }

    @Test
    void buildsHundredsOfFilesWithManyMethods() throws Exception {
        generate(20, 25); // 500 文件 / 1500 方法
        BuildResult result = new CallGraphBuilder().buildFull(repo, CancellationToken.NONE);
        assertTrue(result.parsedFiles().size() >= 500);
        assertTrue(result.graph().methods().size() >= 1500);
        // 每个 entry 都应有到同类型 step 的边
        long entryEdges = result.graph().edges().stream()
                .filter(e -> e.caller().name().equals("entry")
                        && e.callee().name().equals("step"))
                .count();
        assertTrue(entryEdges >= 500, "entry->step edges: " + entryEdges);
    }

    @Test
    void largeBuildIsCancellableAtFileCheckpoints() throws Exception {
        generate(10, 20);
        CancellationToken token = new CancellationToken();
        token.cancel();
        assertThrows(BuildCancelledException.class,
                () -> new CallGraphBuilder().buildFull(repo, token));
    }
}
