package com.github.guoyyit.callgraphjava.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.InitializerDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.guoyyit.callgraphjava.core.CancellationToken;
import com.github.guoyyit.callgraphjava.model.CallKind;
import com.github.guoyyit.callgraphjava.model.CallSite;
import com.github.guoyyit.callgraphjava.model.FieldInfo;
import com.github.guoyyit.callgraphjava.model.MethodId;
import com.github.guoyyit.callgraphjava.model.MethodInfo;
import com.github.guoyyit.callgraphjava.model.ParsedFile;
import com.github.guoyyit.callgraphjava.model.TypeInfo;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 JavaParser 的单文件解析器：抽取类型、字段、方法、局部变量声明类型与调用点。
 * 只记录语法事实，不做跨文件名字解析；类型名的 FQN 化与静态/虚调用的最终归类
 * 在后续接收者解析阶段完成。
 */
public final class JavaSourceParser {

    private final JavaParser javaParser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));

    public ParsedFile parse(Path repoRoot, Path relativePath, CancellationToken token) {
        token.throwIfCancelled();
        Path file = repoRoot.resolve(relativePath);
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }

        var result = javaParser.parse(source);
        if (result.isSuccessful() && result.getResult().isPresent()) {
            return parseCompilationUnit(result.getResult().get(), relativePath.toString(), source);
        }
        return new ParsedFile(relativePath.toString(), "", List.of(), List.of(),
                List.of(), List.of(),
                List.of(), sha256(source));
    }

    private ParsedFile parseCompilationUnit(CompilationUnit cu, String relativePath, String source) {
        String packageName = cu.getPackageDeclaration()
                .map(p -> p.getNameAsString()).orElse("");
        List<String> imports = cu.getImports().stream()
                .filter(i -> !i.isStatic())
                .map(i -> i.isAsterisk() ? i.getNameAsString() + ".*" : i.getNameAsString())
                .toList();
        List<String> staticImports = cu.getImports().stream()
                .filter(i -> i.isStatic() && !i.isAsterisk())
                .map(i -> i.getName().asString())
                .toList();

        List<TypeInfo> types = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();
        List<CallSite> callSites = new ArrayList<>();

        for (TypeDeclaration<?> topLevel : cu.getTypes()) {
            String fqn = packageName.isEmpty() ? topLevel.getNameAsString()
                    : packageName + "." + topLevel.getNameAsString();
            collectType(topLevel, fqn, relativePath, types, methods, callSites);
        }
        return new ParsedFile(relativePath, packageName, imports, staticImports,
                types, methods, callSites,
                sha256(source));
    }

    private void collectType(TypeDeclaration<?> type,
                             String fqn,
                             String sourceFile,
                             List<TypeInfo> outTypes,
                             List<MethodInfo> outMethods,
                             List<CallSite> outCalls) {
        String kind;
        String superClass = null;
        List<String> interfaces = new ArrayList<>();
        if (type instanceof ClassOrInterfaceDeclaration coi) {
            kind = coi.isInterface() ? "interface" : "class";
            if (coi.isInterface()) {
                for (ClassOrInterfaceType t : coi.getExtendedTypes()) {
                    interfaces.add(t.getNameAsString());
                }
            } else {
                superClass = coi.getExtendedTypes().isEmpty()
                        ? null : coi.getExtendedTypes().get(0).getNameAsString();
                for (ClassOrInterfaceType t : coi.getImplementedTypes()) {
                    interfaces.add(t.getNameAsString());
                }
            }
        } else if (type instanceof EnumDeclaration ed) {
            kind = "enum";
            superClass = "java.lang.Enum";
            for (ClassOrInterfaceType t : ed.getImplementedTypes()) {
                interfaces.add(t.getNameAsString());
            }
        } else if (type instanceof RecordDeclaration rd) {
            kind = "record";
            superClass = "java.lang.Record";
            for (ClassOrInterfaceType t : rd.getImplementedTypes()) {
                interfaces.add(t.getNameAsString());
            }
        } else if (type instanceof AnnotationDeclaration) {
            kind = "annotation";
        } else {
            kind = "class";
        }

        List<FieldInfo> fields = new ArrayList<>();
        List<MethodId> declaredMethods = new ArrayList<>();

        for (var member : type.getMembers()) {
            if (member instanceof FieldDeclaration field) {
                for (VariableDeclarator v : field.getVariables()) {
                    fields.add(new FieldInfo(v.getNameAsString(),
                            cleanType(field.getCommonType().asString())));
                }
            } else if (member instanceof MethodDeclaration method) {
                MethodId id = new MethodId(fqn, method.getNameAsString(),
                        descriptorOf(method.getParameters()));
                declaredMethods.add(id);
                Map<String, String> locals = collectLocals(method, type, fqn);
                boolean isStatic = method.hasModifier(Modifier.Keyword.STATIC);
                boolean isAbstract = method.hasModifier(Modifier.Keyword.ABSTRACT)
                        || (!isStatic && method.getBody().isEmpty()
                            && type instanceof ClassOrInterfaceDeclaration coi2 && coi2.isInterface());
                outMethods.add(new MethodInfo(id, isStatic, isAbstract, sourceFile,
                        Map.copyOf(locals), cleanType(method.getType().asString())));
                method.getBody().ifPresent(body ->
                        collectCalls(body, id, sourceFile, fqn, type, outCalls));
            } else if (member instanceof ConstructorDeclaration ctor) {
                MethodId id = new MethodId(fqn, "<init>", descriptorOf(ctor.getParameters()));
                declaredMethods.add(id);
                Map<String, String> locals = new LinkedHashMap<>();
                for (Parameter p : ctor.getParameters()) {
                    locals.put(p.getNameAsString(), cleanType(p.getType().asString()));
                }
                if (!"enum".equals(kind)) {
                    locals.put("this", fqn);
                }
                outMethods.add(new MethodInfo(id, false, false, sourceFile,
                        Map.copyOf(locals), fqn));
                collectCalls(ctor.getBody(), id, sourceFile, fqn, type, outCalls);
            } else if (member instanceof InitializerDeclaration init) {
                String name = init.isStatic() ? "<clinit>" : "<initblock>";
                MethodId id = new MethodId(fqn, name, "()");
                declaredMethods.add(id);
                outMethods.add(new MethodInfo(id, init.isStatic(), false, sourceFile,
                        Map.of(), "void"));
                collectCalls(init.getBody(), id, sourceFile, fqn, type, outCalls);
            }
        }

        outTypes.add(new TypeInfo(fqn, kind, superClass, List.copyOf(interfaces),
                List.copyOf(declaredMethods), List.copyOf(fields)));

        for (var member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nestedType && !isAnonymous(nestedType)) {
                collectType(nestedType, fqn + "." + nestedType.getNameAsString(),
                        sourceFile, outTypes, outMethods, outCalls);
            }
        }
    }

    private static boolean isAnonymous(TypeDeclaration<?> type) {
        return type instanceof ClassOrInterfaceDeclaration c
                && c.getName().getIdentifier().isEmpty();
    }

    private Map<String, String> collectLocals(MethodDeclaration method,
                                              TypeDeclaration<?> owner,
                                              String ownerFqn) {
        Map<String, String> locals = new LinkedHashMap<>();
        for (Parameter p : method.getParameters()) {
            locals.put(p.getNameAsString(), cleanType(p.getType().asString()));
        }
        boolean isStatic = method.hasModifier(Modifier.Keyword.STATIC);
        if (!isStatic && !(owner instanceof ClassOrInterfaceDeclaration coi && coi.isInterface())) {
            locals.put("this", ownerFqn);
        }
        for (VariableDeclarator v : method.findAll(VariableDeclarator.class)) {
            boolean insideAnonymous = v.findAncestor(TypeDeclaration.class)
                    .map(JavaSourceParser::isAnonymous).orElse(false);
            if (!insideAnonymous) {
                locals.put(v.getNameAsString(), cleanType(v.getType().asString()));
            }
        }
        return locals;
    }

    /**
     * 收集方法体内的调用点；匿名类子树中的调用不归属外部方法（当前版本不建模匿名类，
     * 避免把匿名类实现错挂到外部方法上）。
     */
    private void collectCalls(Node root,
                              MethodId enclosing,
                              String sourceFile,
                              String ownerFqn,
                              TypeDeclaration<?> ownerType,
                              List<CallSite> out) {
        for (Node node : root.findAll(Node.class)) {
            if (node != root && node.findAncestor(TypeDeclaration.class)
                    .map(JavaSourceParser::isAnonymous).orElse(false)) {
                continue;
            }
            if (node instanceof ObjectCreationExpr oce) {
                int line = oce.getBegin().map(p -> p.line).orElse(-1);
                out.add(new CallSite(sourceFile, enclosing, line, CallKind.DIRECT, "<init>",
                        oce.getArguments().size(), null, oce.getType().getNameAsString()));
            } else if (node instanceof ExplicitConstructorInvocationStmt ecis) {
                int line = ecis.getBegin().map(p -> p.line).orElse(-1);
                String staticOwner;
                if (ecis.isThis()) {
                    staticOwner = simpleName(ownerFqn);
                } else {
                    staticOwner = (ownerType instanceof ClassOrInterfaceDeclaration c
                            && !c.getExtendedTypes().isEmpty())
                            ? c.getExtendedTypes().get(0).getNameAsString() : "java.lang.Object";
                }
                out.add(new CallSite(sourceFile, enclosing, line, CallKind.DIRECT, "<init>",
                        ecis.getArguments().size(), null, staticOwner));
            } else if (node instanceof MethodCallExpr mce) {
                out.add(methodCall(mce, enclosing, sourceFile, ownerType));
            }
        }
    }

    private CallSite methodCall(MethodCallExpr mce,
                                MethodId enclosing,
                                String sourceFile,
                                TypeDeclaration<?> ownerType) {
        int line = mce.getBegin().map(p -> p.line).orElse(-1);
        int arity = mce.getArguments().size();
        Expression scopeExpr = mce.getScope().orElse(null);
        CallKind kind;
        String scope = null;
        String staticOwner = null;
        if (scopeExpr == null || scopeExpr instanceof ThisExpr) {
            // 无接收者或 this：同类/继承方法，具体是否静态由接收者解析阶段结合 MethodInfo 判定。
            kind = CallKind.VIRTUAL;
            scope = "this";
        } else if (scopeExpr instanceof SuperExpr) {
            kind = CallKind.DIRECT;
            scope = "super";
            staticOwner = (ownerType instanceof ClassOrInterfaceDeclaration c
                    && !c.getExtendedTypes().isEmpty())
                    ? c.getExtendedTypes().get(0).getNameAsString() : "java.lang.Object";
        } else {
            // 类型名静态调用（X.foo()）与表达式虚调用（x.foo()、getX().foo()）在语法阶段
            // 无法可靠区分，保留 scope 原文交由接收者解析器判定。
            kind = CallKind.VIRTUAL;
            scope = scopeExpr.toString();
        }
        return new CallSite(sourceFile, enclosing, line, kind, mce.getNameAsString(),
                arity, scope, staticOwner);
    }

    private String descriptorOf(List<Parameter> parameters) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cleanType(parameters.get(i).getType().asString()));
        }
        return sb.append(')').toString();
    }

    /** 去掉泛型实参与注解：{@code java.util.List<String>} -> {@code java.util.List}，数组维度保留。 */
    public static String cleanType(String raw) {
        String t = raw.trim();
        int lt = t.indexOf('<');
        if (lt >= 0) {
            int depth = 0;
            int end = lt;
            for (int i = lt; i < t.length(); i++) {
                char ch = t.charAt(i);
                if (ch == '<') {
                    depth++;
                } else if (ch == '>') {
                    depth--;
                    if (depth == 0) {
                        end = i + 1;
                        break;
                    }
                }
            }
            t = t.substring(0, lt) + t.substring(end);
        }
        return t.replaceAll("@[A-Za-z0-9_.]+\\s*", "").trim();
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
