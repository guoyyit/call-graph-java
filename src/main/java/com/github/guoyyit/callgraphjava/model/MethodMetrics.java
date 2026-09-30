package com.github.guoyyit.callgraphjava.model;

import java.util.List;

public record MethodMetrics(MethodId method, int inDegree, int outDegree, int sccId,
                            boolean inCycle, List<MethodId> reachable) {
}
