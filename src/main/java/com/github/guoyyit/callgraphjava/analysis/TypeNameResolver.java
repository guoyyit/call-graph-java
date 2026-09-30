package com.github.guoyyit.callgraphjava.analysis;

import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.model.MethodInfo;
import com.github.guoyyit.callgraphjava.model.ParsedFile;
import com.github.guoyyit.callgraphjava.model.TypeInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 类型名字解析与规范化。
 *
 * <p>解析顺序（与 JLS 简化一致）：
 * <ol>
 *   <li>本文件声明的类型（含嵌套，简单名即局部 FQN 的尾段）；</li>
 *   <li>显式单类型 import；</li>
 *   <li>同包内全部类型；</li>
 *   <li>{@code java.lang}；</li>
 *   <li>通配 import 的包（按仓库内存在性匹配）。</li>
 * </ol>
 * 仓库内不存在的外部类型返回 {@code null}；原始文本为 FQN 且仓库存在时原样返回。
 *
 * <p>{@link #canonicalize} 把一个已解析文件里的所有原始类型名（类型层次、方法描述符、
 * 局部变量类型、返回类型）转换为 FQN，外部类型相关信息被置空，保证后续增量/全量结果
 * 只依赖仓库内源码。
 */
public final class TypeNameResolver {

    /** 外部库（仓库内无源码）类型参数的占位符，仅用于维持形参个数；永不会命中仓库内类型。 */
    public static final String EXTERNAL_PLACEHOLDER = "*external*";

    private static final Set<String> PRIMITIVES =
            Set.of("void", "boolean", "byte", "char", "short", "int", "long", "float", "double");

    /** FQN 集合。 */
    private final Set<String> knownFqns;
    /** 简单名 -> FQN（同包名冲突时不覆盖，保留首个；使用方再结合当前包判定）。 */
    private final Map<String, String> globalSimpleNames;
    /** 包名 -> 该包内全部 FQN。 */
    private final Map<String, Set<String>> packageIndex;

    public TypeNameResolver(Set<String> knownFqns) {
        this.knownFqns = Set.copyOf(knownFqns);
        this.globalSimpleNames = new HashMap<>();
        this.packageIndex = new HashMap<>();
        for (String fqn : knownFqns) {
            globalSimpleNames.putIfAbsent(simpleName(fqn), fqn);
            int dot = fqn.lastIndexOf('.');
            String pkg = dot < 0 ? "" : fqn.substring(0, dot);
            packageIndex.computeIfAbsent(pkg, k -> new HashSet<>()).add(fqn);
        }
    }

    public boolean exists(String fqn) {
        return fqn != null && knownFqns.contains(fqn);
    }

    /**
     * 在给定文件上下文中解析原始类型文本。数组（{@code T[]}）会剥离维度后解析再追加维度；
     * 基本类型/void 原样返回；无法在仓库内定位的引用类型返回 {@code null}。
     */
    public String resolve(String raw, ParsedFile context) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        int dims = 0;
        while (text.endsWith("[]")) {
            dims++;
            text = text.substring(0, text.length() - 2).trim();
        }
        String resolved = resolveBase(text, context);
        if (resolved == null) {
            return null;
        }
        return dims == 0 ? resolved : resolved + "[]".repeat(dims);
    }

    private String resolveBase(String text, ParsedFile context) {
        if (PRIMITIVES.contains(text)) {
            return text;
        }
        if (knownFqns.contains(text)) {
            return text;
        }
        // 嵌套类引用：形如 Outer.Inner（无包前缀）时，尝试在本文件声明类型与 import 内补全。
        String firstSegment = text.contains(".") ? text.substring(0, text.indexOf('.')) : text;

        // 1) 本文件声明的类型（包含嵌套：A.B 与声明 FQN 尾段匹配）
        for (TypeInfo local : context.types()) {
            String tail = tailStartsWith(local.fqn(), firstSegment) ? local.fqn() : null;
            if (tail != null) {
                String candidate = extendNested(local.fqn(), text);
                if (candidate != null && knownFqns.contains(candidate)) {
                    return candidate;
                }
            }
        }

        // 2) 显式单类型 import
        for (String imp : context.imports()) {
            if (!imp.endsWith(".*")) {
                if (simpleName(imp).equals(firstSegment)) {
                    String candidate = text.contains(".") ? extendNested(imp, text) : imp;
                    if (knownFqns.contains(candidate)) {
                        return candidate;
                    }
                }
            }
        }

        // 3) 同包
        {
            String candidate = (context.packageName().isEmpty() ? "" : context.packageName() + ".") + text;
            if (knownFqns.contains(candidate)) {
                return candidate;
            }
            String bySimple = samePackageType(firstSegment, context.packageName());
            if (bySimple != null) {
                String nested = extendNested(bySimple, text);
                if (knownFqns.contains(nested)) {
                    return nested;
                }
            }
        }

        // 4) java.lang —— 仅当仓库内声明了同名类型时才可见（外部库不建模，返回 null）
        String javaLang = javaLang(firstSegment);
        if (javaLang != null) {
            return javaLang;
        }

        // 5) 通配 import 包
        for (String imp : context.imports()) {
            if (imp.endsWith(".*")) {
                String pkg = imp.substring(0, imp.length() - 2);
                String candidate = pkg + "." + text;
                if (knownFqns.contains(candidate)) {
                    return candidate;
                }
            }
        }

        // 6) 全局唯一简单名兜底（跨包引用但漏写 import 的情况不处理；这里仅处理恰好唯一者）
        String global = globalSimpleNames.get(firstSegment);
        if (global != null) {
            String nested = extendNested(global, text);
            if (nested != null && knownFqns.contains(nested)) {
                return nested;
            }
        }
        return null;
    }

    private String samePackageType(String simpleName, String packageName) {
        Set<String> types = packageIndex.getOrDefault(packageName, Set.of());
        String found = null;
        for (String fqn : types) {
            if (simpleName(fqn).equals(simpleName)) {
                if (found != null && !found.equals(fqn)) {
                    return null;
                }
                found = fqn;
            }
        }
        return found;
    }

    private String javaLang(String simpleName) {
        String fqn = "java.lang." + simpleName;
        return knownFqns.contains(fqn) ? fqn : null;
    }

    private static boolean tailStartsWith(String fqn, String simpleFirstSegment) {
        int dot = fqn.lastIndexOf('.');
        String tail = dot < 0 ? fqn : fqn.substring(dot + 1);
        return tail.equals(simpleFirstSegment);
    }

    /**
     * 若 {@code text} 是 {@code baseFqn} 尾段的嵌套延伸（base 已是简单名形式），
     * 返回拼接后的候选 FQN；text 本身等于 base 尾段时返回 base。
     */
    private static String extendNested(String baseFqn, String text) {
        int dot = baseFqn.lastIndexOf('.');
        String baseTail = dot < 0 ? baseFqn : baseFqn.substring(dot + 1);
        if (text.equals(baseTail)) {
            return baseFqn;
        }
        if (text.startsWith(baseTail + ".")) {
            String suffix = text.substring(baseTail.length());
            return baseFqn + suffix;
        }
        return null;
    }

    static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /** 把单个文件中的所有原始类型名规范化为 FQN；外部类型字段置空。 */
    public ParsedFile canonicalize(ParsedFile file) {
        List<TypeInfo> types = new ArrayList<>();
        // 先规范化方法，建立 旧Fqn -> 规范 MethodInfo 映射，供类型声明与调用点复用同一 ID
        Map<String, MethodInfo> canonicalMethods = new LinkedHashMap<>();
        for (MethodInfo m : file.methods()) {
            Map<String, String> locals = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : m.locals().entrySet()) {
                String resolved = resolve(e.getValue(), file);
                locals.put(e.getKey(), resolved == null ? null : stripArrays(resolved));
            }
            MethodId canonicalId = canonicalizeMethodId(m.id(), file);
            String returnType = m.returnType() == null ? null
                    : stripArraysOrNull(resolve(m.returnType(), file));
            canonicalMethods.put(m.id().fqn(),
                    new MethodInfo(canonicalId, m.isStatic(), m.isAbstract(), m.sourceFile(),
                            java.util.Collections.unmodifiableMap(new HashMap<>(locals)),
                            returnType));
        }

        for (TypeInfo t : file.types()) {
            String superClass = t.superClass() == null ? null : resolve(t.superClass(), file);
            List<String> interfaces = new ArrayList<>();
            for (String itf : t.interfaces()) {
                String resolved = resolve(itf, file);
                if (resolved != null) {
                    interfaces.add(resolved);
                }
            }
            List<com.github.guoyyit.callgraphjava.model.FieldInfo> fields = new ArrayList<>();
            for (com.github.guoyyit.callgraphjava.model.FieldInfo field : t.fields()) {
                String fieldType = stripArraysOrNull(resolve(field.rawType(), file));
                fields.add(new com.github.guoyyit.callgraphjava.model.FieldInfo(
                        field.name(), fieldType));
            }
            List<MethodId> declared = new ArrayList<>();
            for (MethodId declaredId : t.declaredMethods()) {
                MethodInfo canonical = canonicalMethods.get(declaredId.fqn());
                declared.add(canonical == null ? declaredId : canonical.id());
            }
            types.add(new TypeInfo(t.fqn(), t.kind(), superClass, interfaces,
                    List.copyOf(declared), List.copyOf(fields)));
        }

        List<MethodInfo> methods = new ArrayList<>();
        for (MethodInfo m : file.methods()) {
            methods.add(canonicalMethods.get(m.id().fqn()));
        }
        // 为没有显式构造器的具体类/枚举/记录合成默认构造器 <init>()，
        // 使 new X() 能正确成边；接口/注解不合成。
        for (TypeInfo type : types) {
            boolean hasExplicitCtor = type.declaredMethods().stream()
                    .anyMatch(id -> "<init>".equals(id.name()));
            boolean instantiable = "class".equals(type.kind()) || "enum".equals(type.kind());
            if (!hasExplicitCtor && instantiable) {
                MethodId synthInit = new MethodId(type.fqn(), "<init>", "()");
                MethodInfo synth = new MethodInfo(synthInit, false, false,
                        file.relativePath(), Map.of("this", type.fqn()), type.fqn());
                methods.add(synth);
                canonicalMethods.put(synthInit.fqn(), synth);
                replaceDeclaredMethods(type, types, synthInit);
            }
        }

        List<com.github.guoyyit.callgraphjava.model.CallSite> calls =
                new ArrayList<>();
        for (var cs : file.callSites()) {
            MethodId enclosing = canonicalMethods.containsKey(cs.enclosingMethod().fqn())
                    ? canonicalMethods.get(cs.enclosingMethod().fqn()).id()
                    : canonicalizeMethodId(cs.enclosingMethod(), file);
            String staticOwner = cs.staticTargetOwner() == null ? null
                    : stripArraysOrNull(resolve(cs.staticTargetOwner(), file));
            calls.add(new com.github.guoyyit.callgraphjava.model.CallSite(
                    cs.sourceFile(), enclosing, cs.line(), cs.kind(), cs.targetName(),
                    cs.arity(), cs.scope(), staticOwner));
        }

        return new ParsedFile(file.relativePath(), file.packageName(), file.imports(),
                file.staticImports(),
                List.copyOf(types), List.copyOf(methods), List.copyOf(calls), file.sha256());
    }

    private MethodId canonicalizeMethodId(MethodId id, ParsedFile file) {
        // 方法身份按 owner+name+参数数归一：能解析到仓库内类型的参数写 FQN，
        // 解析不到的（外部库/未解析类型）退化为占位符以维持参数个数。
        List<String> params = new ArrayList<>();
        for (String p : id.parameterTypes()) {
            String resolved = resolve(p, file);
            params.add(resolved != null ? stripArrays(resolved) : EXTERNAL_PLACEHOLDER);
        }
        String descriptor = "(" + String.join(",", params) + ")";
        return new MethodId(id.ownerType(), id.name(), descriptor);
    }

    private void replaceDeclaredMethods(TypeInfo type, List<TypeInfo> types, MethodId synthInit) {
        for (int i = 0; i < types.size(); i++) {
            TypeInfo t = types.get(i);
            if (t.fqn().equals(type.fqn())) {
                List<MethodId> withInit = new ArrayList<>(t.declaredMethods());
                withInit.add(synthInit);
                types.set(i, new TypeInfo(t.fqn(), t.kind(), t.superClass(), t.interfaces(),
                        List.copyOf(withInit), t.fields()));
                return;
            }
        }
    }

    static String stripArrays(String type) {
        return type.endsWith("[]") ? stripArrays(type.substring(0, type.length() - 2).trim()) : type;
    }

    private static String stripArraysOrNull(String type) {
        return type == null ? null : stripArrays(type);
    }
}
