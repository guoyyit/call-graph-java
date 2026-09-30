package com.github.guoyyit.callgraphjava.model;

import java.util.List;

public record MethodNode(MethodId id, String file, boolean abstractMethod, boolean staticMethod,
                         boolean privateMethod, String returnType, List<String> paramNames) {
    public MethodNode {
        paramNames = paramNames == null ? List.of() : List.copyOf(paramNames);
    }
}
