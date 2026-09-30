package com.github.guoyyit.callgraphjava.web;

import com.github.guoyyit.callgraphjava.core.BuildCancelledException;
import com.github.guoyyit.callgraphjava.core.CancellationToken;
import com.github.guoyyit.callgraphjava.graph.SccResult;
import com.github.guoyyit.callgraphjava.model.CallEdge;
import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.service.CallGraphService;
import com.github.guoyyit.callgraphjava.service.GraphSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * REST 接口。更新接口共用服务内的单写者锁；取消令牌按「最近一次构建」管理，
 * POST /cancel 仅请求协作式取消，不回滚已提交快照。
 */
@RestController
@RequestMapping("/api/callgraph")
public final class GraphController {

    private final CallGraphService service;
    private final AtomicReference<CancellationToken> activeToken = new AtomicReference<>();

    public GraphController(CallGraphService service) {
        this.service = service;
    }

    @PostMapping("/build/full")
    public ResponseEntity<?> buildFull() {
        CancellationToken token = beginBuild();
        try {
            GraphSnapshot snapshot = service.buildFull(token);
            return ResponseEntity.ok(versionBody(snapshot));
        } catch (BuildCancelledException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("cancelled", true));
        } finally {
            activeToken.compareAndSet(token, null);
        }
    }

    @PostMapping("/build/incremental")
    public ResponseEntity<?> buildIncremental() {
        CancellationToken token = beginBuild();
        try {
            GraphSnapshot snapshot = service.buildIncremental(token);
            return ResponseEntity.ok(versionBody(snapshot));
        } catch (BuildCancelledException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("cancelled", true));
        } finally {
            activeToken.compareAndSet(token, null);
        }
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancel() {
        CancellationToken token = activeToken.get();
        if (token != null) {
            token.cancel();
        }
        return Map.of("cancelled", token != null);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        GraphSnapshot snapshot = service.currentSnapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", service.currentVersion());
        if (snapshot != null) {
            body.put("methods", snapshot.result().graph().methods().size());
            body.put("edges", snapshot.result().graph().edges().size());
            body.put("files", snapshot.fileHashes().size());
        }
        return body;
    }

    @GetMapping("/callers")
    public ResponseEntity<?> callers(@RequestParam String owner,
                                     @RequestParam String name,
                                     @RequestParam(defaultValue = "()") String descriptor) {
        GraphSnapshot snapshot = service.currentSnapshot();
        if (snapshot == null) {
            return noSnapshot();
        }
        MethodId id = new MethodId(owner, name, descriptor);
        return ResponseEntity.ok(snapshot.queries().callers(id).stream()
                .map(MethodId::fqn).sorted().toList());
    }

    @GetMapping("/callees")
    public ResponseEntity<?> callees(@RequestParam String owner,
                                     @RequestParam String name,
                                     @RequestParam(defaultValue = "()") String descriptor) {
        GraphSnapshot snapshot = service.currentSnapshot();
        if (snapshot == null) {
            return noSnapshot();
        }
        MethodId id = new MethodId(owner, name, descriptor);
        List<Map<String, Object>> result = snapshot.result().graph()
                .outgoingEdges(id).stream().map(this::edgeBody).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/reachable")
    public ResponseEntity<?> reachable(@RequestParam String owner,
                                       @RequestParam String name,
                                       @RequestParam(defaultValue = "()") String descriptor,
                                       @RequestParam(required = false) String targetOwner,
                                       @RequestParam(required = false) String targetName,
                                       @RequestParam(defaultValue = "()") String targetDescriptor) {
        GraphSnapshot snapshot = service.currentSnapshot();
        if (snapshot == null) {
            return noSnapshot();
        }
        MethodId source = new MethodId(owner, name, descriptor);
        if (targetName != null && targetOwner != null) {
            MethodId target = new MethodId(targetOwner, targetName, targetDescriptor);
            List<MethodId> path = snapshot.queries().findPath(source, target);
            return ResponseEntity.ok(Map.of(
                    "reachable", !path.isEmpty(),
                    "path", path.stream().map(MethodId::fqn).toList()));
        }
        return ResponseEntity.ok(snapshot.queries()
                .reachableFrom(source, CancellationToken.NONE).stream()
                .map(MethodId::fqn).sorted().toList());
    }

    @GetMapping("/scc")
    public ResponseEntity<?> scc() {
        GraphSnapshot snapshot = service.currentSnapshot();
        if (snapshot == null) {
            return noSnapshot();
        }
        SccResult scc = snapshot.scc(CancellationToken.NONE);
        return ResponseEntity.ok(Map.of(
                "recursiveMethods", scc.recursiveMethods().stream()
                        .map(MethodId::fqn).sorted().toList(),
                "cycles", scc.cycles().stream()
                        .map(c -> c.stream().map(MethodId::fqn).sorted().toList()).toList()));
    }

    @GetMapping("/cross-package")
    public ResponseEntity<?> crossPackage() {
        GraphSnapshot snapshot = service.currentSnapshot();
        if (snapshot == null) {
            return noSnapshot();
        }
        return ResponseEntity.ok(snapshot.queries().crossPackageEdges().stream()
                .map(this::edgeBody).toList());
    }

    private CancellationToken beginBuild() {
        CancellationToken token = new CancellationToken();
        activeToken.set(token);
        return token;
    }

    private Map<String, Object> versionBody(GraphSnapshot snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", snapshot == null ? -1 : snapshot.version());
        return body;
    }

    private Map<String, Object> edgeBody(CallEdge edge) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("caller", edge.caller().fqn());
        body.put("callee", edge.callee().fqn());
        body.put("kind", edge.kind().name());
        body.put("line", edge.line());
        body.put("sourceFile", edge.sourceFile());
        return body;
    }

    private ResponseEntity<?> noSnapshot() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "no snapshot; run a build first"));
    }
}
