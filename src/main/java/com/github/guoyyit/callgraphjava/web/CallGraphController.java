package com.github.guoyyit.callgraphjava.web;

import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.model.Edge;
import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.model.MethodMetrics;
import com.github.guoyyit.callgraphjava.service.BuildManager;
import com.github.guoyyit.callgraphjava.service.CallGraphService;
import com.github.guoyyit.callgraphjava.service.QueryService;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/callgraph")
public class CallGraphController {
    private final CallGraphService graphService;
    private final QueryService queryService;
    private final BuildManager buildManager;

    public CallGraphController(CallGraphService graphService, QueryService queryService,
                               BuildManager buildManager) {
        this.graphService = graphService;
        this.queryService = queryService;
        this.buildManager = buildManager;
    }

    /**
     * Starts a full build asynchronously. The returned buildId can be used to
     * poll {@code GET /builds/{buildId}} and to cancel the build while it is
     * still running. {@code revision} is the revision of the snapshot that is
     * currently being served; the revision produced by the build is reported
     * by the build status endpoint once the build completes.
     */
    @PostMapping("/build")
    public Map<String, Object> build() {
        String buildId = buildManager.submitBuild();
        return Map.of("buildId", buildId,
                "status", BuildManager.Status.RUNNING.name().toLowerCase(),
                "revision", graphService.snapshot().revision());
    }

    @PostMapping("/update")
    public Map<String, Object> update() {
        ProgressToken token = new ProgressToken();
        var snapshot = graphService.update(token);
        return Map.of("revision", snapshot.revision());
    }

    @GetMapping("/builds/{buildId}")
    public ResponseEntity<Map<String, Object>> buildStatus(@PathVariable String buildId) {
        return buildManager.info(buildId)
                .map(info -> {
                    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("buildId", info.buildId());
                    body.put("status", info.status().name().toLowerCase());
                    body.put("revision", info.revision());
                    if (info.error() != null) {
                        body.put("error", info.error());
                    }
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/builds/{buildId}/cancel")
    public ResponseEntity<Map<String, String>> cancel(@PathVariable String buildId) {
        return switch (buildManager.cancel(buildId)) {
            case CANCEL_REQUESTED -> ResponseEntity.ok(Map.of("status", "cancelRequested"));
            case ALREADY_COMPLETED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "alreadyCompleted"));
            case ALREADY_CANCELLED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "alreadyCancelled"));
            case ALREADY_FAILED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "alreadyFailed"));
            case NOT_FOUND -> ResponseEntity.notFound().build();
        };
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
