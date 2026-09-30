package com.github.guoyyit.callgraphjava.web;

import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.model.Edge;
import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.model.MethodMetrics;
import com.github.guoyyit.callgraphjava.service.CallGraphService;
import com.github.guoyyit.callgraphjava.service.QueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/callgraph")
public class CallGraphController {
    private final CallGraphService graphService;
    private final QueryService queryService;
    private final Map<String, ProgressToken> runningBuilds = new ConcurrentHashMap<>();

    public CallGraphController(CallGraphService graphService, QueryService queryService) {
        this.graphService = graphService;
        this.queryService = queryService;
    }

    @PostMapping("/build")
    public Map<String, Object> build() {
        String buildId = java.util.UUID.randomUUID().toString();
        ProgressToken token = new ProgressToken();
        runningBuilds.put(buildId, token);
        graphService.build(graphService.repositoryRoot(), token);
        runningBuilds.remove(buildId);
        return Map.of("buildId", buildId, "revision", graphService.snapshot().revision());
    }

    @PostMapping("/update")
    public Map<String, Object> update() {
        ProgressToken token = new ProgressToken();
        var snapshot = graphService.update(token);
        return Map.of("revision", snapshot.revision());
    }

    @PostMapping("/builds/{buildId}/cancel")
    public ResponseEntity<Map<String, String>> cancel(@PathVariable String buildId) {
        ProgressToken token = runningBuilds.get(buildId);
        if (token == null) {
            return ResponseEntity.notFound().build();
        }
        token.cancel();
        return ResponseEntity.ok(Map.of("status", "cancelRequested"));
    }

    @GetMapping("/snapshot")
    public Map<String, Object> snapshot() {
        var snapshot = graphService.snapshot();
        return Map.of("revision", snapshot.revision(),
                "typeCount", snapshot.types().size(),
                "methodCount", snapshot.methods().size(),
                "edgeCount", snapshot.edges().size(),
                "fileCount", snapshot.files().size());
    }

    @GetMapping("/methods/{owner}/{name}")
    public List<Edge> callees(@PathVariable String owner, @PathVariable String name,
                              @RequestParam(defaultValue = "") List<String> params) {
        return queryService.callees(new MethodId(owner, name, params));
    }

    @GetMapping("/callers/{owner}/{name}")
    public List<Edge> callers(@PathVariable String owner, @PathVariable String name,
                              @RequestParam(defaultValue = "") List<String> params) {
        return queryService.callers(new MethodId(owner, name, params));
    }

    @GetMapping("/reachable/{owner}/{name}")
    public Set<MethodId> reachable(@PathVariable String owner, @PathVariable String name,
                                   @RequestParam(defaultValue = "") List<String> params) {
        return queryService.reachable(new MethodId(owner, name, params));
    }

    @GetMapping("/metrics/{owner}/{name}")
    public MethodMetrics metrics(@PathVariable String owner, @PathVariable String name,
                                 @RequestParam(defaultValue = "") List<String> params) {
        return queryService.metrics(new MethodId(owner, name, params));
    }

    @GetMapping("/cycles")
    public Set<MethodId> cycles() {
        return queryService.methodsInCycles();
    }

    @GetMapping("/scc")
    public Map<String, Integer> scc() {
        return queryService.stronglyConnectedComponents();
    }

    @GetMapping("/cross-package")
    public List<Edge> crossPackage() {
        return queryService.crossPackageEdges();
    }
}
