package com.github.guoyyit.callgraphjava.tests;

import com.github.guoyyit.callgraphjava.build.GraphBuilder;
import com.github.guoyyit.callgraphjava.build.ProgressToken;
import com.github.guoyyit.callgraphjava.model.Edge;
import com.github.guoyyit.callgraphjava.model.GraphSnapshot;
import com.github.guoyyit.callgraphjava.model.MethodId;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualDispatchTest {
    private Path fixture(String name) throws URISyntaxException {
        return Path.of(getClass().getClassLoader().getResource("fixtures/" + name).toURI());
    }

    @Test
    void virtualCallResolvesAllConcreteCandidates() throws URISyntaxException {
        GraphSnapshot snapshot = new GraphBuilder()
                .buildFull(fixture("shapes"), ProgressToken.neverCancelled());
        MethodId render = new MethodId("pkgq.Canvas", "render", List.of("pkgp.Shape"));
        List<Edge> areaEdges = snapshot.edges().stream()
                .filter(edge -> edge.caller().equals(render))
                .filter(edge -> edge.callee().name().equals("area"))
                .toList();

        Set<String> targets = areaEdges.stream()
                .map(edge -> edge.callee().owner())
                .collect(Collectors.toSet());
        assertTrue(targets.contains("pkgp.Circle"), "Circle.area must be a CHA target");
        assertTrue(targets.contains("pkgp.Square"), "Square.area must be a CHA target");
        assertTrue(targets.contains("pkgp.AbstractShape"),
                "AbstractShape.area is concrete and must be a target");
        assertEquals(3, targets.size(), "interface Shape.area is abstract and must be excluded");
    }

    @Test
    void constructorAndInterfaceTypesAreRecorded() throws URISyntaxException {
        GraphSnapshot snapshot = new GraphBuilder()
                .buildFull(fixture("shapes"), ProgressToken.neverCancelled());
        assertEquals("interface", snapshot.types().get("pkgp.Shape").kind());
        assertTrue(snapshot.methods().containsKey("pkgp.Circle#<init>(double)"));
    }
}
