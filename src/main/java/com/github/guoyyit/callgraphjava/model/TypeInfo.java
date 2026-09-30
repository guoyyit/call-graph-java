package com.github.guoyyit.callgraphjava.model;

import java.util.List;

public record TypeInfo(String qualifiedName, String kind, String superClass, List<String> interfaces,
                       String file, boolean abstractType, String outerClass) {
    public TypeInfo {
        interfaces = interfaces == null ? List.of() : List.copyOf(interfaces);
    }

    public boolean isInterface() {
        return "interface".equals(kind);
    }
}
