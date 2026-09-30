package com.github.guoyyit.callgraphjava.model;

public record LocalVar(MethodId method, String name, String declaredType, String initKind,
                       String initText) {
}
