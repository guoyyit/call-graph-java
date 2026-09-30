package com.github.guoyyit.callgraphjava.parse;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.guoyyit.callgraphjava.model.ParsedFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class SourceParser {
    static final String RC_UNQUALIFIED = "UNQUALIFIED";
    static final String RC_THIS = "THIS";
    static final String RC_SUPER = "SUPER";
    static final String RC_TYPE = "TYPE";
    static final String RC_EXPR = "EXPR";
    static final String RC_CTOR = "CTOR";

    private final JavaParser javaParser = new JavaParser();

    public ParsedFile parse(Path file) {
        return parse(file, file.toString());
    }

    public ParsedFile parse(Path file, String displayPath) {
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
        String hash = sha256(content);
        ParseResult<CompilationUnit> result = javaParser.parse(content);
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            throw new IllegalArgumentException("failed to parse " + file + ": " + result.getProblems());
        }
        CompilationUnit cu = result.getResult().get();
        String packageName = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        List<String> importedSimple = cu.getImports().stream()
                .filter(imp -> !imp.isAsterisk())
                .map(imp -> imp.getNameAsString())
                .toList();

        List<com.github.guoyyit.callgraphjava.model.TypeInfo> types = new ArrayList<>();
        List<com.github.guoyyit.callgraphjava.model.MethodNode> methods = new ArrayList<>();
        List<com.github.guoyyit.callgraphjava.model.CallSite> callSites = new ArrayList<>();
        List<com.github.guoyyit.callgraphjava.model.FieldInfo> fields = new ArrayList<>();
        List<com.github.guoyyit.callgraphjava.model.LocalVar> locals = new ArrayList<>();

        String relativePath = displayPath;
        for (TypeDeclaration<?> typeDecl : cu.findAll(TypeDeclaration.class)) {
            String typeFqn = typeDecl.getFullyQualifiedName().orElse(packageName + "." + typeDecl.getNameAsString());
            types.add(buildTypeInfo(typeDecl, typeFqn, relativePath));
            collectFields(typeDecl, typeFqn, fields);
            for (CallableDeclaration<?> callable : callablesOf(typeDecl)) {
                com.github.guoyyit.callgraphjava.model.MethodId callerId = buildMethodId(typeFqn, callable);
                methods.add(buildMethodNode(callerId, relativePath, callable));
                collectLocals(callerId, callable, locals);
                collectCalls(callerId, relativePath, callable, callSites);
            }
        }
        return new ParsedFile(relativePath, hash, types, methods, callSites, fields, locals,
                importedSimple, packageName);
    }

    private void collectCalls(com.github.guoyyit.callgraphjava.model.MethodId callerId, String file,
                              CallableDeclaration<?> callable,
                              List<com.github.guoyyit.callgraphjava.model.CallSite> sink) {
        for (MethodCallExpr call : callable.findAll(MethodCallExpr.class)) {
            if (insideNestedType(callable, call)) {
                continue;
            }
            int line = call.getBegin().map(pos -> pos.line).orElse(-1);
            int arity = call.getArguments().size();
            String name = call.getNameAsString();
            if (call.getScope().isPresent()) {
                Expression scope = call.getScope().get();
                if (scope.isSuperExpr()) {
                    sink.add(site(callerId, file, line, name, arity, RC_SUPER, "",
                            com.github.guoyyit.callgraphjava.model.CallKind.SUPER));
                } else if (scope.isThisExpr()) {
                    sink.add(site(callerId, file, line, name, arity, RC_THIS, "",
                            com.github.guoyyit.callgraphjava.model.CallKind.VIRTUAL));
                } else if (looksLikeTypeName(scope)) {
                    sink.add(site(callerId, file, line, name, arity, RC_TYPE, scope.toString(),
                            com.github.guoyyit.callgraphjava.model.CallKind.STATIC));
                } else {
                    sink.add(site(callerId, file, line, name, arity, RC_EXPR, scope.toString(),
                            com.github.guoyyit.callgraphjava.model.CallKind.VIRTUAL));
                }
            } else {
                sink.add(site(callerId, file, line, name, arity, RC_UNQUALIFIED, "",
                        com.github.guoyyit.callgraphjava.model.CallKind.VIRTUAL));
            }
        }
        for (ObjectCreationExpr creation : callable.findAll(ObjectCreationExpr.class)) {
            if (insideNestedType(callable, creation)) {
                continue;
            }
            int line = creation.getBegin().map(pos -> pos.line).orElse(-1);
            sink.add(site(callerId, file, line, "<init>", creation.getArguments().size(), RC_CTOR,
                    creation.getType().getNameAsString(),
                    com.github.guoyyit.callgraphjava.model.CallKind.CONSTRUCTOR));
        }
        for (ExplicitConstructorInvocationStmt invocation :
                callable.findAll(ExplicitConstructorInvocationStmt.class)) {
            int line = invocation.getBegin().map(pos -> pos.line).orElse(-1);
            if (invocation.isThis()) {
                sink.add(site(callerId, file, line, "<init>", invocation.getArguments().size(), RC_THIS, "",
                        com.github.guoyyit.callgraphjava.model.CallKind.CONSTRUCTOR));
            } else {
                sink.add(site(callerId, file, line, "<init>", invocation.getArguments().size(), RC_SUPER, "",
                        com.github.guoyyit.callgraphjava.model.CallKind.SUPER));
            }
        }
    }

    private boolean insideNestedType(CallableDeclaration<?> owner, com.github.javaparser.ast.Node node) {
        var maybeOwnerType = owner.findAncestor(TypeDeclaration.class);
        var nodeType = node.findAncestor(TypeDeclaration.class);
        return maybeOwnerType.isPresent() && nodeType.isPresent()
                && !maybeOwnerType.get().equals(nodeType.get());
    }

    private com.github.guoyyit.callgraphjava.model.CallSite site(
            com.github.guoyyit.callgraphjava.model.MethodId caller, String file, int line, String name, int arity,
            String receiverKind, String receiverText,
            com.github.guoyyit.callgraphjava.model.CallKind hint) {
        return new com.github.guoyyit.callgraphjava.model.CallSite(caller, file,
                line, name, arity, receiverKind, receiverText, hint);
    }

    private boolean looksLikeTypeName(Expression scope) {
        String text = scope.toString();
        if (text.isBlank()) {
            return false;
        }
        String firstSegment = text.split("\\.", 2)[0];
        if (firstSegment.isEmpty() || !Character.isUpperCase(firstSegment.charAt(0))) {
            return false;
        }
        return !scope.isMethodCallExpr() && !scope.isObjectCreationExpr() && !scope.isFieldAccessExpr()
                || scope.isNameExpr();
    }

    private void collectLocals(com.github.guoyyit.callgraphjava.model.MethodId method,
                               CallableDeclaration<?> callable,
                               List<com.github.guoyyit.callgraphjava.model.LocalVar> sink) {
        for (Parameter parameter : callable.getParameters()) {
            sink.add(new com.github.guoyyit.callgraphjava.model.LocalVar(method, parameter.getNameAsString(),
                    normalizeType(parameter.getType()), null, null));
        }
        for (VariableDeclarationExpr declaration : callable.findAll(VariableDeclarationExpr.class)) {
            if (insideNestedType(callable, declaration)) {
                continue;
            }
            for (VariableDeclarator variable : declaration.getVariables()) {
                String initKind = null;
                String initText = null;
                if (variable.getInitializer().isPresent()
                        && variable.getInitializer().get().isObjectCreationExpr()) {
                    initKind = "NEW";
                    initText = normalizeType(variable.getInitializer().get().asObjectCreationExpr().getType());
                }
                sink.add(new com.github.guoyyit.callgraphjava.model.LocalVar(method,
                        variable.getNameAsString(), normalizeType(variable.getType()), initKind, initText));
            }
        }
    }

    private List<CallableDeclaration<?>> callablesOf(TypeDeclaration<?> typeDecl) {
        List<CallableDeclaration<?>> callables = new ArrayList<>(typeDecl.getMethods());
        callables.addAll(typeDecl.getConstructors());
        return callables;
    }

    private com.github.guoyyit.callgraphjava.model.MethodId buildMethodId(String owner,
                                                                          CallableDeclaration<?> callable) {
        String name = callable instanceof ConstructorDeclaration ? "<init>"
                : ((MethodDeclaration) callable).getNameAsString();
        List<String> params = callable.getParameters().stream()
                .map(parameter -> normalizeType(parameter.getType()))
                .toList();
        return new com.github.guoyyit.callgraphjava.model.MethodId(owner, name, params);
    }

    private com.github.guoyyit.callgraphjava.model.MethodNode buildMethodNode(
            com.github.guoyyit.callgraphjava.model.MethodId id, String file,
            CallableDeclaration<?> callable) {
        boolean abstractMethod = callable instanceof MethodDeclaration methodDecl
                && (methodDecl.isAbstract() || methodDecl.getBody().isEmpty());
        boolean staticMethod = callable.hasModifier(Modifier.Keyword.STATIC);
        boolean privateMethod = callable.hasModifier(Modifier.Keyword.PRIVATE);
        String returnType = callable instanceof MethodDeclaration methodDecl
                ? normalizeType(methodDecl.getType()) : ownerSimple(id.owner());
        List<String> paramNames = callable.getParameters().stream()
                .map(Parameter::getNameAsString)
                .toList();
        return new com.github.guoyyit.callgraphjava.model.MethodNode(id, file, abstractMethod, staticMethod,
                privateMethod, returnType, paramNames);
    }

    private void collectFields(TypeDeclaration<?> typeDecl, String owner,
                               List<com.github.guoyyit.callgraphjava.model.FieldInfo> sink) {
        for (FieldDeclaration field : typeDecl.getFields()) {
            boolean staticField = field.hasModifier(Modifier.Keyword.STATIC);
            for (VariableDeclarator variable : field.getVariables()) {
                sink.add(new com.github.guoyyit.callgraphjava.model.FieldInfo(owner,
                        variable.getNameAsString(), normalizeType(variable.getType()), staticField));
            }
        }
    }

    private com.github.guoyyit.callgraphjava.model.TypeInfo buildTypeInfo(TypeDeclaration<?> typeDecl,
                                                                          String fqn, String file) {
        String kind;
        if (typeDecl instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classOrInterface
                && classOrInterface.isInterface()) {
            kind = "interface";
        } else if (typeDecl.isEnumDeclaration()) {
            kind = "enum";
        } else if (typeDecl.isRecordDeclaration()) {
            kind = "record";
        } else {
            kind = "class";
        }
        String superClass = null;
        List<String> interfaces = new ArrayList<>();
        if (typeDecl instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration classDecl) {
            superClass = classDecl.getExtendedTypes().stream().findFirst()
                    .map(type -> normalizeType(type)).orElse(null);
            interfaces = classDecl.getImplementedTypes().stream()
                    .map(type -> normalizeType(type))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList<String>::new));
        } else if (typeDecl.isEnumDeclaration()) {
            superClass = "java.lang.Enum";
        }
        boolean abstractType = typeDecl.hasModifier(Modifier.Keyword.ABSTRACT)
                || (kind.equals("interface"));
        String outerClass = null;
        java.util.Optional<TypeDeclaration> ancestor = typeDecl.findAncestor(TypeDeclaration.class);
        if (ancestor.isPresent()) {
            outerClass = ((TypeDeclaration<?>) ancestor.get()).getFullyQualifiedName().orElse(null);
        }
        return new com.github.guoyyit.callgraphjava.model.TypeInfo(fqn, kind, superClass, interfaces,
                file, abstractType, outerClass);
    }

    private String normalizeType(com.github.javaparser.ast.type.Type type) {
        if (type.isClassOrInterfaceType()) {
            ClassOrInterfaceType classType = type.asClassOrInterfaceType();
            String name = classType.getScope().map(scope -> scope.asString() + ".").orElse("")
                    + classType.getNameAsString();
            return name;
        }
        if (type.isArrayType()) {
            return normalizeType(type.asArrayType().getComponentType());
        }
        return type.asString();
    }

    private String ownerSimple(String owner) {
        int dot = owner.lastIndexOf('.');
        return dot < 0 ? owner : owner.substring(dot + 1);
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
