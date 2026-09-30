package com.github.guoyyit.callgraphjava.analysis;

import com.github.guoyyit.callgraphjava.core.CancellationToken;
import com.github.guoyyit.callgraphjava.graph.CallGraph;
import com.github.guoyyit.callgraphjava.model.CallEdge;
import com.github.guoyyit.callgraphjava.model.CallSite;
import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.model.MethodInfo;
import com.github.guoyyit.callgraphjava.model.ParsedFile;
import com.github.guoyyit.callgraphjava.model.TypeInfo;
import com.github.guoyyit.callgraphjava.parser.JavaSourceParser;
import com.github.guoyyit.callgraphjava.parser.SourceScanner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调用图组装器。
 *
 * <p>全量与增量共用同一条「解析表 -> 规范化 -> 接收者推导 -> CHA 分派 -> 出边」管线，
 * 唯一差别只在解析表的来源：全量解析全部文件；增量只解析变更文件、复用其余文件的
 * 解析产物。由于分派始终基于替换后的完整解析表重算，增量结果天然与全量重建一致，
 * 不会保留任何过期调用边。
 *
 * <p>内存上界：逐文件解析，同一时刻仅有一个 AST；解析完成后 AST 即被释放，
 * 驻留内存的只有规范化后的精简模型（类型/方法/调用点）。可通过取消令牌在每个文件、
 * 每个调用点检查点中断。
 */
public final class CallGraphBuilder {

    private final SourceScanner scanner = new SourceScanner();
    private final JavaSourceParser parser = new JavaSourceParser();

    public BuildResult buildFull(Path repoRoot, CancellationToken token) {
        Map<String, ParsedFile> parsed = new LinkedHashMap<>();
        List<Path> sources = scanner.scan(repoRoot);
        for (Path relative : sources) {
            token.throwIfCancelled();
            ParsedFile file = parser.parse(repoRoot, relative, token);
            parsed.put(file.relativePath(), file);
        }
        return assemble(parsed, token);
    }

    public BuildResult buildIncremental(Path repoRoot,
                                        Map<String, ParsedFile> previousParsedFiles,
                                        Map<String, ParsedFile> changedFiles,
                                        Set<String> deletedFiles,
                                        CancellationToken token) {
        Map<String, ParsedFile> merged = new LinkedHashMap<>(previousParsedFiles);
        for (String deleted : deletedFiles) {
            merged.remove(deleted);
        }
        for (Map.Entry<String, ParsedFile> e : changedFiles.entrySet()) {
            token.throwIfCancelled();
            merged.put(e.getKey(), e.getValue());
        }
        return assemble(merged, token);
    }

    /** 仅解析指定相对路径集合（增量时使用）。 */
    public Map<String, ParsedFile> parseFiles(Path repoRoot,
                                              List<Path> relativePaths,
                                              CancellationToken token) {
        Map<String, ParsedFile> result = new LinkedHashMap<>();
        for (Path relative : relativePaths) {
            token.throwIfCancelled();
            result.put(relative.toString(), parser.parse(repoRoot, relative, token));
        }
        return result;
    }

    public List<Path> listSources(Path repoRoot) {
        return scanner.scan(repoRoot);
    }

    /** 从（合并后的）完整解析表构建调用图。 */
    public BuildResult assemble(Map<String, ParsedFile> rawFiles, CancellationToken token) {
        Set<String> knownFqns = new LinkedHashSet<>();
        for (ParsedFile file : rawFiles.values()) {
            for (TypeInfo type : file.types()) {
                knownFqns.add(type.fqn());
            }
        }
        TypeNameResolver resolver = new TypeNameResolver(knownFqns);

        Map<String, ParsedFile> canonical = new LinkedHashMap<>();
        for (ParsedFile file : rawFiles.values()) {
            token.throwIfCancelled();
            canonical.put(file.relativePath(), resolver.canonicalize(file));
        }

        List<TypeInfo> allTypes = canonical.values().stream()
                .flatMap(f -> f.types().stream()).toList();
        TypeHierarchy hierarchy = TypeHierarchy.build(allTypes, canonical.values());

        ReceiverTypeResolver receiverResolver = new ReceiverTypeResolver(resolver);
        receiverResolver.resolveReceivers(canonical, hierarchy);

        DispatchResolver dispatchResolver = new DispatchResolver(hierarchy);
        Set<CallEdge> edges = new LinkedHashSet<>();
        Set<MethodId> allMethods = new LinkedHashSet<>();
        for (ParsedFile file : canonical.values()) {
            for (MethodInfo method : file.methods()) {
                token.throwIfCancelled();
                allMethods.add(method.id());
            }
            for (CallSite callSite : file.callSites()) {
                token.throwIfCancelled();
                List<MethodId> targets = dispatchResolver.resolve(callSite);
                callSite.setResolvedTargets(targets);
                for (MethodId target : targets) {
                    edges.add(new CallEdge(callSite.enclosingMethod(), target,
                            callSite.kind(), callSite.line(), callSite.sourceFile()));
                }
            }
        }

        CallGraph graph = CallGraph.of(edges, allMethods);
        Map<String, String> hashes = new LinkedHashMap<>();
        for (ParsedFile file : canonical.values()) {
            hashes.put(file.relativePath(), file.sha256());
        }
        return new BuildResult(graph, canonical, hierarchy, Map.copyOf(hashes));
    }
}
