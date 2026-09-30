package com.github.guoyyit.callgraphjava.analysis;

import com.github.guoyyit.callgraphjava.model.CallKind;
import com.github.guoyyit.callgraphjava.model.CallSite;
import com.github.guoyyit.callgraphjava.model.FieldInfo;
import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.model.MethodInfo;
import com.github.guoyyit.callgraphjava.model.ParsedFile;
import com.github.guoyyit.callgraphjava.model.TypeInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 过程内接收者静态类型推导。
 *
 * <p>处理顺序：
 * <ol>
 *   <li>DIRECT 调用（new/super/this(...)）的接收者类型即静态属主，构造器调用不做虚分派；</li>
 *   <li>无 scope 或 {@code this}：先判断属主类型内是否存在同名 static 方法
 *       （静态方法不可被重写），命中则改为 DIRECT；否则接收者为属主类型；</li>
 *   <li>scope 为简单名：优先局部变量/形参，其次本类型及祖先字段；
 *       若简单名解析为类型 FQN（X.util()），视为静态调用 DIRECT；</li>
 *   <li>scope 为限定名：字段访问（a.b）沿字段声明类型解析；
 *       类型限定静态调用（Type.method()）判为 DIRECT；</li>
 *   <li>scope 为链式调用（getX().m()）：按方法返回类型逐步推导。</li>
 * </ol>
 * 推导不到仓库内类型时接收者为 {@code null}，该调用点不产生边（外部库调用）。
 */
public final class ReceiverTypeResolver {

    private final TypeNameResolver typeNameResolver;

    private Map<String, ParsedFile> files;
    private TypeHierarchy hierarchy;

    public ReceiverTypeResolver(TypeNameResolver typeNameResolver) {
        this.typeNameResolver = typeNameResolver;
    }

    public void resolveReceivers(Map<String, ParsedFile> files) {
        resolveReceivers(files, TypeHierarchy.build(
                files.values().stream().flatMap(f -> f.types().stream()).toList(),
                files.values()));
    }

    public void resolveReceivers(Map<String, ParsedFile> files, TypeHierarchy hierarchy) {
        this.files = files;
        this.hierarchy = hierarchy;
        for (ParsedFile file : files.values()) {
            for (CallSite callSite : file.callSites()) {
                resolveOne(file, callSite);
            }
        }
    }

    private void resolveOne(ParsedFile file, CallSite callSite) {
        if (callSite.kind() == CallKind.DIRECT
                && ("<init>".equals(callSite.targetName()) || "super".equals(callSite.scope()))) {
            callSite.setReceiverType(callSite.staticTargetOwner());
            return;
        }
        String scope = callSite.scope();
        if (scope == null || scope.equals("this")) {
            resolveUnqualified(file, callSite);
            return;
        }
        resolveScoped(file, callSite, scope);
    }

    private void resolveUnqualified(ParsedFile file, CallSite callSite) {
        String ownerFqn = callSite.enclosingMethod().ownerType();
        // 静态方法（同类声明）优先：X.foo() 省略 X 时仍应是静态绑定。
        MethodInfo staticHit = findDeclaredMethod(ownerFqn, callSite.targetName(),
                callSite.arity(), true);
        if (staticHit != null) {
            callSite.setKind(CallKind.DIRECT);
            callSite.setStaticTargetOwner(ownerFqn);
            callSite.setReceiverType(ownerFqn);
            return;
        }
        // import static a.B.m 后无接收者调用 m()：在导入的声明类型上定位静态方法。
        for (String staticImport : file.staticImports()) {
            int memberDot = staticImport.lastIndexOf('.');
            if (memberDot < 0
                    || !staticImport.substring(memberDot + 1).equals(callSite.targetName())) {
                continue;
            }
            String ownerFqnOfImport = staticImport.substring(0, memberDot);
            MethodInfo imported = findDeclaredMethod(
                    ownerFqnOfImport, callSite.targetName(), callSite.arity(), true);
            if (imported != null) {
                callSite.setKind(CallKind.DIRECT);
                callSite.setStaticTargetOwner(ownerFqnOfImport);
                callSite.setReceiverType(ownerFqnOfImport);
                return;
            }
        }
        callSite.setReceiverType(ownerFqn);
    }

    private void resolveScoped(ParsedFile file, CallSite callSite, String scope) {
        // 1) scope 本身就是类型名（含 FQN），静态调用
        String asType = typeNameResolver.resolve(scope, file);
        if (asType != null) {
            callSite.setKind(CallKind.DIRECT);
            callSite.setStaticTargetOwner(asType);
            callSite.setReceiverType(asType);
            return;
        }

        MethodInfo enclosing = findMethodInfo(callSite.enclosingMethod());

        // 2) 链式调用 a.b().c()  /  getX().m()
        if (scope.endsWith(")")) {
            String chainType = resolveChainType(file, scope, enclosing);
            callSite.setReceiverType(chainType);
            return;
        }

        // 3) 字段访问链 a.b 或 a.b.c
        if (scope.contains(".")) {
            String fieldType = resolveFieldAccess(file, scope, enclosing);
            callSite.setReceiverType(fieldType);
            return;
        }

        // 4) 简单名：先判类型名（静态调用），再查局部变量/形参，最后字段
        String simpleType = typeNameResolver.resolve(scope, file);
        if (simpleType != null) {
            callSite.setKind(CallKind.DIRECT);
            callSite.setStaticTargetOwner(simpleType);
            callSite.setReceiverType(simpleType);
            return;
        }
        if (enclosing != null && enclosing.locals().containsKey(scope)) {
            callSite.setReceiverType(enclosing.locals().get(scope));
            return;
        }
        String fieldType = findFieldType(callSite.enclosingMethod().ownerType(), scope);
        callSite.setReceiverType(fieldType);
    }

    /** 解析形如 {@code a.b().c()} 的接收者表达式，从最左变量/字段出发，按方法返回类型推进。 */
    private String resolveChainType(ParsedFile file, String expression, MethodInfo enclosing) {
        // 自右向左拆出方法名，再自左向右求值。
        String head = expression;
        java.util.List<String> invoked = new java.util.ArrayList<>();
        while (head.endsWith(")")) {
            int open = head.lastIndexOf('(');
            String before = head.substring(0, open);
            int dot = before.lastIndexOf('.');
            String methodName = dot < 0 ? before : before.substring(dot + 1);
            invoked.add(0, methodName);
            head = dot < 0 ? "" : before.substring(0, dot);
        }
        String currentType;
        if (head.isEmpty()) {
            currentType = enclosing == null ? null : enclosing.id().ownerType();
        } else if (head.contains(".")) {
            currentType = resolveFieldAccess(file, head, enclosing);
        } else if (enclosing != null && enclosing.locals().containsKey(head)) {
            currentType = enclosing.locals().get(head);
        } else {
            String asType = typeNameResolver.resolve(head, file);
            if (asType != null) {
                currentType = asType;
            } else {
                currentType = findFieldType(enclosing == null ? null
                        : enclosing.id().ownerType(), head);
            }
        }
        for (String methodName : invoked) {
            if (currentType == null) {
                return null;
            }
            MethodInfo target = findDeclaredMethod(currentType, methodName, -1, false);
            if (target == null) {
                target = findDeclaredMethod(currentType, methodName, -1, true);
            }
            if (target == null) {
                return null;
            }
            currentType = target.returnType();
        }
        return currentType;
    }

    /** 解析字段访问链 {@code a.b.c}：最左是局部变量/字段，后续每段按字段声明类型推进。 */
    private String resolveFieldAccess(ParsedFile file, String expression, MethodInfo enclosing) {
        String[] parts = expression.split("\\.");
        String currentType;
        String first = parts[0];
        String asType = typeNameResolver.resolve(first, file);
        if (asType != null) {
            // 外层是类型限定，剩下第一段其实是静态字段；继续按字段链处理
            currentType = asType;
        } else if (enclosing != null && enclosing.locals().containsKey(first)) {
            currentType = enclosing.locals().get(first);
        } else {
            currentType = findFieldType(enclosing == null ? null
                    : enclosing.id().ownerType(), first);
        }
        int start = asType != null ? 1 : 1;
        for (int i = start; i < parts.length; i++) {
            if (currentType == null) {
                return null;
            }
            currentType = findFieldType(currentType, parts[i]);
        }
        return currentType;
    }

    private MethodInfo findMethodInfo(MethodId id) {
        if (id == null) {
            return null;
        }
        return hierarchy == null ? null : hierarchy.methodInfo(id);
    }

    private MethodInfo findDeclaredMethod(String ownerFqn, String name, int arity, boolean requireStatic) {
        if (ownerFqn == null || hierarchy == null) {
            return null;
        }
        for (MethodId visible : hierarchy.visibleMethods(ownerFqn, name, arity)) {
            MethodInfo info = hierarchy.methodInfo(visible);
            if (info == null) {
                continue;
            }
            if (!requireStatic || info.isStatic()) {
                return info;
            }
        }
        return null;
    }

    private String findFieldType(String startType, String fieldName) {
        if (startType == null || hierarchy == null) {
            return null;
        }
        for (String ancestor : hierarchy.ancestors(startType)) {
            TypeInfo info = hierarchy.typeInfo(ancestor);
            if (info == null) {
                continue;
            }
            for (FieldInfo field : info.fields()) {
                if (field.name().equals(fieldName)) {
                    return field.rawType();
                }
            }
        }
        return null;
    }

    public String receiverOf(CallSite callSite) {
        return callSite.receiverType();
    }
}
