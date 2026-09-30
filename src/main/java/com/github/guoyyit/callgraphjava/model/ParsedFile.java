package com.github.guoyyit.callgraphjava.model;

import java.util.List;

public record ParsedFile(String path, String contentHash, List<TypeInfo> types,
                         List<MethodNode> methods, List<CallSite> callSites,
                         List<FieldInfo> fields, List<LocalVar> locals,
                         List<String> importedSimple, String packageName) {
    public ParsedFile {
        types = copy(types);
        methods = copy(methods);
        callSites = copy(callSites);
        fields = copy(fields);
        locals = copy(locals);
        importedSimple = importedSimple == null ? List.of() : List.copyOf(importedSimple);
    }

    private static <T> List<T> copy(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
