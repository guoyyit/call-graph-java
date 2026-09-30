package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.build.GraphBuilder;
import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.model.Edge;
import com.github.guoyyit.callgraphjava.model.GraphSnapshot;
import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.service.QueryService;
import com.github.guoyyit.callgraphjava.store.GraphStore;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CycleAndPackageTest {
    private Path fixture(String name) throws URISyntaxException {
        return Path.of(getClass().getClassLoader().getResource("fixtures/" + name).toURI());
    }

    private QueryService queryFor(String fixture) throws URISyntaxException {
        GraphSnapshot snapshot = new GraphBuilder()
                .buildFull(fixture(fixture), ProgressToken.neverCancelled());
        GraphStore store = new GraphStore();
        store.commit(snapshot);
        return new QueryService(store);
    }

    @Test
    void detectsMutualAndSelfCycles() throws URISyntaxException {
        QueryService query = queryFor("cycle");
        MethodId a = new MethodId("cycle.ALoop", "a", List.of("cycle.BLoop"));
        MethodId b = new MethodId("cycle.BLoop", "b", List.of("cycle.ALoop"));
        MethodId self = MethodId.of("cycle.BLoop", "self");

        assertTrue(query.isRecursive(a));
        assertTrue(query.isRecursive(b));
        assertTrue(query.isRecursive(self));
        assertEquals(query.stronglyConnectedComponents().get(a.key()),
                query.stronglyConnectedComponents().get(b.key()));
        assertTrue(query.canReach(a, b));
        assertTrue(query.canReach(b, a));
    }

    @Test
    void classifiesCrossPackageEdges() throws URISyntaxException {
        QueryService query = queryFor("shapes");
        List<Edge> cross = query.crossPackageEdges();
        assertFalse(cross.isEmpty());
        assertTrue(cross.stream().anyMatch(edge ->
                edge.caller().owner().startsWith("pkgq.")
                        && edge.callee().owner().startsWith("pkgp.")));
    }
}
