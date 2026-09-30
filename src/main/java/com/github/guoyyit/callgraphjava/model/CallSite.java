package com.github.guoyyit.callgraphjava.model;

public record CallSite(MethodId caller, String file, int line, String name, int arity,
                       String receiverKind, String receiverText, CallKind hint) {
    public String siteId() {
        return caller.key() + "@" + file + ":" + line + ":" + name;
    }
}
