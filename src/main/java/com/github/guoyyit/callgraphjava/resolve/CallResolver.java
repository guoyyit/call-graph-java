package com.github.guoyyit.callgraphjava.resolve;

import com.github.guoyyit.callgraphjava.model.CallKind;
import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.model.MethodNode;
import com.github.guoyyit.callgraphjava.analysis.TypeHierarchy;
import com.github.guoyyit.callgraphjava.model.CallSite;
import com.github.guoyyit.callgraphjava.model.Edge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CallResolver {
    private final TypeHierarchy hierarchy;
    private final NameResolver names;
    private final Map<String, MethodNode> methods;

    public CallResolver(TypeHierarchy hierarchy, NameResolver names) {
        this.hierarchy = hierarchy;
        this.names = names;
        this.methods = Map.of();
    }

    public CallResolver(TypeHierarchy hierarchy, NameResolver names, Map<String, MethodNode> methodRegistry) {
        this.hierarchy = hierarchy;
        this.names = names;
        this.methods = methodRegistry;
    }

    public List<Edge> resolve(CallSite site) {
        String receiverType = names.inferReceiverType(site);
        if (receiverType == null) {
            return List.of();
        }
        return switch (site.hint()) {
            case STATIC -> directEdge(site, receiverType, CallKind.STATIC);
            case CONSTRUCTOR -> resolveConstructor(site, receiverType);
            case SUPER -> resolveSuper(site, receiverType);
            case DIRECT -> directEdge(site, receiverType, CallKind.DIRECT);
            case VIRTUAL, INTERFACE -> resolveVirtual(site, receiverType);
        };
    }

    private List<Edge> resolveConstructor(CallSite site, String receiverType) {
        MethodId target = findDeclaredMethod(receiverType, "<init>", site.arity());
        if (target != null) {
            return List.of(new Edge(site.caller(), target, CallKind.CONSTRUCTOR));
        }
        if (hierarchy.typeInfo(receiverType) == null) {
            // The constructed type is not declared in this repository (for
            // example a JDK type such as java.util.ArrayList). Calls on
            // external types never produce call edges.
            return List.of();
        }
        // In-repository type without an explicit constructor: keep the edge to
        // its implicit default constructor.
        return List.of(new Edge(site.caller(),
                new MethodId(receiverType, "<init>", defaultParams(site.arity())),
                CallKind.CONSTRUCTOR));
    }

    private List<Edge> resolveSuper(CallSite site, String ownerType) {
        String name = site.name();
        int arity = site.arity();
        for (String parent : hierarchy.allSupertypes(ownerType)) {
            MethodId target = findDeclaredMethod(parent, name, arity);
            if (target != null) {
                return List.of(new Edge(site.caller(), target, CallKind.SUPER));
            }
        }
        return List.of();
    }

    private List<Edge> directEdge(CallSite site, String ownerType, CallKind kind) {
        MethodId target = findAccessibleMethod(ownerType, site.name(), site.arity());
        if (target == null) {
            return List.of();
        }
        return List.of(new Edge(site.caller(), target, kind));
    }

    private List<Edge> resolveVirtual(CallSite site, String staticType) {
        List<String> targets = new ArrayList<>();
        targets.add(staticType);
        targets.addAll(hierarchy.allSubtypes(staticType));
        Map<String, MethodId> distinct = new LinkedHashMap<>();
        for (String runtimeType : targets) {
            MethodId dispatched = resolveDispatch(runtimeType, site.name(), site.arity());
            if (dispatched != null) {
                distinct.putIfAbsent(dispatched.key(), dispatched);
            }
        }
        CallKind kind = isInterfaceType(staticType) ? CallKind.INTERFACE : CallKind.VIRTUAL;
        List<Edge> edges = new ArrayList<>();
        for (MethodId target : distinct.values()) {
            edges.add(new Edge(site.caller(), target, kind));
        }
        return edges;
    }

    private MethodId resolveDispatch(String type, String name, int arity) {
        String current = type;
        while (current != null) {
            MethodId declared = findDeclaredMethod(current, name, arity);
            if (declared != null) {
                MethodNode node = methods.get(declared.key());
                if (node != null && !node.abstractMethod()) {
                    return declared;
                }
            }
            current = parentClass(current);
        }
        for (String parentInterface : hierarchy.allSupertypes(type)) {
            MethodId declared = findDeclaredMethod(parentInterface, name, arity);
            if (declared != null) {
                MethodNode node = methods.get(declared.key());
                if (node != null && !node.abstractMethod()) {
                    return declared;
                }
            }
        }
        return null;
    }

    private MethodId findAccessibleMethod(String type, String name, int arity) {
        MethodId declared = findDeclaredMethod(type, name, arity);
        if (declared != null) {
            return declared;
        }
        for (String parent : hierarchy.allSupertypes(type)) {
            MethodId inherited = findDeclaredMethod(parent, name, arity);
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    private MethodId findDeclaredMethod(String type, String name, int arity) {
        for (MethodNode node : methods.values()) {
            MethodId id = node.id();
            if (id.owner().equals(type) && id.name().equals(name) && id.arity() == arity) {
                return id;
            }
        }
        return null;
    }

    private String parentClass(String type) {
        var info = hierarchy.typeInfo(type);
        return info == null ? null : info.superClass();
    }

    private boolean isAbstract(String type) {
        var info = hierarchy.typeInfo(type);
        return info != null && info.abstractType();
    }

    private boolean isInterfaceType(String type) {
        var info = hierarchy.typeInfo(type);
        return info != null && info.isInterface();
    }

    private List<String> defaultParams(int arity) {
        List<String> params = new ArrayList<>();
        for (int index = 0; index < arity; index++) {
            params.add("?");
        }
        return params;
    }
}
