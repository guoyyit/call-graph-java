package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.build.GraphBuilder;
import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.model.CallKind;
import com.github.guoyyit.callgraphjava.model.Edge;
import com.github.guoyyit.callgraphjava.model.GraphSnapshot;
import com.github.guoyyit.callgraphjava.model.MethodId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalCallTargetTest {
    private static final MethodId BUILD = new MethodId("ext.Toolbox", "build", List.of());

    @Test
    void jdkTypeConstructorCallsProduceNoEdges() {
        GraphSnapshot snapshot = new GraphBuilder().buildFull(
                Path.of("src/test/resources/fixtures/external"), ProgressToken.neverCancelled());

        List<Edge> outgoing = snapshot.edges().stream()
                .filter(edge -> edge.caller().equals(BUILD))
                .toList();

        assertFalse(outgoing.isEmpty(), "in-repository constructor edges must be kept");
        assertTrue(outgoing.stream().noneMatch(edge -> edge.callee().owner().startsWith("java.")),
                "no edge may target a method of a type outside the repository: " + outgoing);
        assertTrue(snapshot.edges().stream()
                        .noneMatch(edge -> edge.callee().owner().equals("java.util.ArrayList")),
                "new java.util.ArrayList<>() must not produce an edge");
    }

    @Test
    void inRepositoryConstructorEdgesStayIntact() {
        GraphSnapshot snapshot = new GraphBuilder().buildFull(
                Path.of("src/test/resources/fixtures/external"), ProgressToken.neverCancelled());

        MethodId helperCtor = new MethodId("ext.Helper", "<init>", List.of("java.lang.String"));
        MethodId defaultCtor = new MethodId("ext.PlainPojo", "<init>", List.of());

        assertTrue(snapshot.edges().contains(new Edge(BUILD, helperCtor, CallKind.CONSTRUCTOR)),
                "explicit in-repository constructor edge expected");
        assertTrue(snapshot.edges().contains(new Edge(BUILD, defaultCtor, CallKind.CONSTRUCTOR)),
                "implicit default constructor edge for in-repository type expected");
    }
}
