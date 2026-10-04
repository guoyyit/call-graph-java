package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.build.GraphBuilder;
import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.model.CallKind;
import com.github.guoyyit.callgraphjava.model.Edge;
import com.github.guoyyit.callgraphjava.model.GraphSnapshot;
import com.github.guoyyit.callgraphjava.model.MethodId;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalConstructorCallTest {
    private Path fixture(String name) throws URISyntaxException {
        return Path.of(getClass().getClassLoader().getResource("fixtures/" + name).toURI());
    }

    @Test
    void jdkConstructorCallsProduceNoEdges() throws URISyntaxException {
        GraphSnapshot snapshot = new GraphBuilder()
                .buildFull(fixture("jdkcalls"), ProgressToken.neverCancelled());
        MethodId make = new MethodId("acme.Factory", "make", List.of());

        List<Edge> outgoing = snapshot.edges().stream()
                .filter(edge -> edge.caller().equals(make))
                .toList();
        assertFalse(outgoing.isEmpty(), "in-repo constructor edge must exist");
        assertTrue(outgoing.stream().noneMatch(edge -> edge.callee().owner().startsWith("java.")),
                "no edge may target JDK types, got: " + outgoing);
        assertTrue(outgoing.stream().noneMatch(edge -> edge.callee().owner().equals("java.util.ArrayList")),
                "new java.util.ArrayList<>() must not produce an edge");
        assertTrue(outgoing.stream().anyMatch(edge -> edge.kind() == CallKind.CONSTRUCTOR
                        && edge.callee().owner().equals("acme.Widget")
                        && edge.callee().name().equals("<init>")),
                "in-repo constructor edge must be kept");
    }

    @Test
    void allEdgeTargetsAreDeclaredInRepo() throws URISyntaxException {
        GraphSnapshot snapshot = new GraphBuilder()
                .buildFull(fixture("jdkcalls"), ProgressToken.neverCancelled());
        for (Edge edge : snapshot.edges()) {
            assertTrue(snapshot.methods().containsKey(edge.callee().key()),
                    "edge target must be a declared method: " + edge.callee());
        }
    }
}
