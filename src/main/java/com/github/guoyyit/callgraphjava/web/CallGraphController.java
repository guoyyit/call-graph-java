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

import java.util.LinkedHashMap;
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

    @PostMapping("/build")
    public Map<String, Object> build() {
        String buildId = buildManager.startBuild();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("buildId", buildId);
        response.put("status", "running");
        return response;
    }

    @GetMapping("/builds/{buildId}")
    public ResponseEntity<Map<String, Object>> buildStatus(@PathVariable String buildId) {
        BuildManager.BuildInfo info = buildManager.info(buildId);
        if (info == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("buildId", info.buildId());
        body.put("status", switch (info.status()) {
            case RUNNING -> "running";
            case CANCELLED -> "cancelled";
            case COMPLETED -> "completed";
            case FAILED -> "failed";
        });
        if (info.revision() != null) {
            body.put("revision", info.revision());
        }
        if (info.error() != null) {
            body.put("error", info.error());
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/update")
    public Map<String, Object> update() {
        ProgressToken token = new ProgressToken();
        var snapshot = graphService.update(token);
        return Map.of("revision", snapshot.revision());
    }

    @PostMapping("/builds/{buildId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String buildId) {
        BuildManager.CancelResult result = buildManager.cancel(buildId);
        return switch (result) {
            case CANCEL_REQUESTED -> ResponseEntity.ok(
                    Map.of("buildId", buildId, "status", "cancelRequested"));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("buildId", buildId, "status", "notFound"));
            case ALREADY_COMPLETED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("buildId", buildId, "status", "alreadyCompleted"));
            case ALREADY_CANCELLED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("buildId", buildId, "status", "alreadyCancelled"));
            case ALREADY_FAILED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("buildId", buildId, "status", "alreadyFailed"));
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
