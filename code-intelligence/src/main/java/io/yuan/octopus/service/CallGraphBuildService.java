package io.yuan.octopus.service;

import io.yuan.octopus.common.CallType;
import io.yuan.octopus.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CallGraphBuildService {

    private static final Logger log = LoggerFactory.getLogger(CallGraphBuildService.class);

    public CallGraph buildCallGraph(ParseResult parseResult) {
        Map<String, ParsedClass> typeRegistry = buildTypeRegistry(parseResult);
        return buildCallGraph(parseResult, typeRegistry);
    }

    /**
     * Builds a call graph from the given parse result, using {@code globalTypeRegistry} for
     * cross-module callee resolution.  Only classes from {@code parseResult} are iterated as
     * callers; callee nodes from {@code globalTypeRegistry} that are not in the local parse result
     * are included in the returned edge set but NOT in the returned node set — callers are
     * responsible for storing cross-module callee nodes separately.
     *
     * @param parseResult        parse result for the module being analysed (callers)
     * @param globalTypeRegistry combined type registry from all modules in the workspace;
     *                           used to resolve callee classes that live in sibling modules
     */
    public CallGraph buildCallGraph(ParseResult parseResult, Map<String, ParsedClass> globalTypeRegistry) {
        // Local type registry: used to iterate callers and filter returned nodes
        Map<String, ParsedClass> localTypeRegistry = buildTypeRegistry(parseResult);

        // Inheritance graph built from the global registry for better interface resolution
        Map<String, List<String>> inheritanceGraph = buildInheritanceGraph(globalTypeRegistry);

        Set<MethodNode> nodes = new HashSet<>();
        Set<CallEdge> edges = new HashSet<>();

        // Iterate only local (caller-module) classes; use globalTypeRegistry for callee lookup
        for (ParsedClass parsedClass : localTypeRegistry.values()) {
            for (ParsedMethod parsedMethod : parsedClass.methods()) {
                int lambdaIndex = 0;
                for (MethodCall methodCall : parsedMethod.methodCalls()) {
                    // Handle Lambda
                    if (methodCall.callType() == CallType.LAMBDA) {
                        MethodNode callerNode = buildMethodNode(parsedClass, parsedMethod);
                        String lambdaSignature = parsedClass.qualifiedName() + ".lambda$" + parsedMethod.name() + "$" + lambdaIndex;
                        MethodNode lambdaNode = new MethodNode(
                                parsedClass.qualifiedName(),
                                "lambda$" + parsedMethod.name() + "$" + lambdaIndex,
                                methodCall.argumentTypes(),
                                "void",
                                "",
                                lambdaSignature,
                                parsedClass.sourceFilePath(),
                                parsedMethod.sourceCode()
                        );
                        nodes.add(callerNode);
                        nodes.add(lambdaNode);
                        edges.add(new CallEdge(callerNode, lambdaNode, CallType.LAMBDA, methodCall.lineNumber()));
                        lambdaIndex++;
                        continue;
                    }

                    // Resolve callee using global registry — allows cross-module calls
                    String targetClassName = methodCall.targetClassName();
                    if (!globalTypeRegistry.containsKey(targetClassName)) {
                        // Third-party or unresolvable — skip
                        continue;
                    }

                    MethodNode callerNode = buildMethodNode(parsedClass, parsedMethod);
                    nodes.add(callerNode);

                    ParsedClass targetClass = globalTypeRegistry.get(targetClassName);
                    ParsedMethod targetMethod = findMethod(targetClass, methodCall, globalTypeRegistry);

                    // Handle interface/super calls — resolve to actual implementing classes
                    boolean resolvedToImpl = false;
                    if (methodCall.callType() == CallType.INTERFACE || methodCall.callType() == CallType.SUPER) {
                        String ifaceSimple = targetClassName.contains(".")
                                ? targetClassName.substring(targetClassName.lastIndexOf('.') + 1)
                                : targetClassName;
                        List<String> implementors = inheritanceGraph.getOrDefault(targetClassName,
                                inheritanceGraph.getOrDefault(ifaceSimple, List.of()));
                        for (String implClassName : implementors) {
                            ParsedClass implClass = globalTypeRegistry.get(implClassName);
                            if (implClass == null) continue;
                            ParsedMethod implMethod = findMatchingMethod(implClass, methodCall);
                            if (implMethod != null) {
                                MethodNode implNode = buildMethodNode(implClass, implMethod);
                                // Only add callee node to the nodes set if it belongs to the local module;
                                // cross-module callee nodes will be stored under their own projectId.
                                if (localTypeRegistry.containsKey(implClass.qualifiedName())) {
                                    nodes.add(implNode);
                                }
                                edges.add(new CallEdge(callerNode, implNode, methodCall.callType(), methodCall.lineNumber()));
                                resolvedToImpl = true;
                            }
                        }
                    }

                    // Only add direct edge if no concrete implementors were found
                    if (!resolvedToImpl && targetMethod != null) {
                        MethodNode calleeNode = buildMethodNode(targetClass, targetMethod);
                        if (localTypeRegistry.containsKey(targetClass.qualifiedName())) {
                            nodes.add(calleeNode);
                        }
                        edges.add(new CallEdge(callerNode, calleeNode, methodCall.callType(), methodCall.lineNumber()));
                    }
                }
            }
        }

        return new CallGraph(nodes, edges);
    }

    /**
     * Build type registry mapping qualifiedName → ParsedClass.
     * Package-private so orchestration services can build a combined (global) registry
     * from multiple modules' ParseResults.
     */
    public Map<String, ParsedClass> buildTypeRegistry(ParseResult parseResult) {
        Map<String, ParsedClass> registry = new HashMap<>();
        for (ParsedFile parsedFile : parseResult.parsedFiles()) {
            for (ParsedClass parsedClass : parsedFile.classes()) {
                registry.put(parsedClass.qualifiedName(), parsedClass);
            }
        }
        return registry;
    }

    private record SourceMetadata(String sourceFilePath, String sourceCode, int parameterCount) {}

    private Map<String, SourceMetadata> buildSourceMetadata(ParseResult parseResult) {
        Map<String, SourceMetadata> sourceBySignature = new HashMap<>();
        for (ParsedFile parsedFile : parseResult.parsedFiles()) {
            for (ParsedClass parsedClass : parsedFile.classes()) {
                for (ParsedMethod method : parsedClass.methods()) {
                    sourceBySignature.put(method.signature(),
                            new SourceMetadata(parsedClass.sourceFilePath(), method.sourceCode(),
                                    method.parameterTypes().size()));
                }
            }
        }
        return sourceBySignature;
    }

    private Map<String, List<SourceMetadata>> buildSourceMetadataByClassAndMethod(ParseResult parseResult) {
        Map<String, List<SourceMetadata>> sourceByClassAndMethod = new HashMap<>();
        for (ParsedFile parsedFile : parseResult.parsedFiles()) {
            for (ParsedClass parsedClass : parsedFile.classes()) {
                for (ParsedMethod method : parsedClass.methods()) {
                    String key = parsedClass.qualifiedName() + "#" + method.name();
                    sourceByClassAndMethod.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(new SourceMetadata(parsedClass.sourceFilePath(), method.sourceCode(),
                                    method.parameterTypes().size()));
                }
            }
        }
        return sourceByClassAndMethod;
    }

    private SourceMetadata findSourceMetadata(
            PSIMethodNode psiMethod,
            Map<String, SourceMetadata> sourceBySignature,
            Map<String, List<SourceMetadata>> sourceByClassAndMethod) {
        SourceMetadata exact = sourceBySignature.get(psiMethod.signature());
        if (exact != null) {
            return exact;
        }

        List<SourceMetadata> candidates = sourceByClassAndMethod.getOrDefault(
                psiMethod.qualifiedClassName() + "#" + psiMethod.methodName(), List.of());
        List<SourceMetadata> sameArity = candidates.stream()
                .filter(candidate -> candidate.parameterCount() == psiMethod.parameterTypes().size())
                .toList();
        return sameArity.size() == 1 ? sameArity.get(0) : null;
    }

    /**
     * Build inheritance graph: interface/parent class → list of implementing/child class qualified names.
     */
    private Map<String, List<String>> buildInheritanceGraph(Map<String, ParsedClass> typeRegistry) {
        Map<String, List<String>> graph = new HashMap<>();
        for (ParsedClass parsedClass : typeRegistry.values()) {
            // Map parent class → child
            if (parsedClass.parentClass() != null && !parsedClass.parentClass().isEmpty()) {
                graph.computeIfAbsent(parsedClass.parentClass(), k -> new ArrayList<>())
                        .add(parsedClass.qualifiedName());
            }
            // Map interfaces → implementor
            if (parsedClass.interfaces() != null) {
                for (String iface : parsedClass.interfaces()) {
                    graph.computeIfAbsent(iface, k -> new ArrayList<>())
                            .add(parsedClass.qualifiedName());
                }
            }
        }
        return graph;
    }

    /**
     * Build a MethodNode for the caller (the method containing the call).
     * Uses the FQN-based signature from {@code ParsedMethod} (set by {@link CodeParseService}),
     * which is consistent with how callee signatures are resolved and stored.
     */
    private MethodNode buildMethodNode(ParsedClass parsedClass, ParsedMethod parsedMethod) {
        String modifiers = String.join(" ", parsedMethod.modifiers());
        return new MethodNode(
                parsedClass.qualifiedName(),
                parsedMethod.name(),
                parsedMethod.parameterTypes(),
                parsedMethod.returnType(),
                modifiers,
                parsedMethod.signature(),
                parsedClass.sourceFilePath(),
                parsedMethod.sourceCode()
        );
    }

    /**
     * Find a matching method in the target class for the given MethodCall.
     * If not found directly, walk up the parent class chain.
     */
    private ParsedMethod findMethod(ParsedClass targetClass, MethodCall methodCall,
                                    Map<String, ParsedClass> typeRegistry) {
        ParsedMethod found = findMatchingMethod(targetClass, methodCall);
        if (found != null) {
            return found;
        }
        // Try parent class chain
        String parentClassName = targetClass.parentClass();
        while (parentClassName != null && !parentClassName.isEmpty()) {
            ParsedClass parentClass = typeRegistry.get(parentClassName);
            if (parentClass == null) {
                break;
            }
            found = findMatchingMethod(parentClass, methodCall);
            if (found != null) {
                return found;
            }
            parentClassName = parentClass.parentClass();
        }
        return null;
    }

    /**
     * Find a method in the given class that matches the MethodCall's target method name and argument types.
     */
    private ParsedMethod findMatchingMethod(ParsedClass parsedClass, MethodCall methodCall) {
        for (ParsedMethod method : parsedClass.methods()) {
            if (method.name().equals(methodCall.targetMethodName())
                    && method.parameterTypes().equals(methodCall.argumentTypes())) {
                return method;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // PSI-backed call graph construction (replaces JavaParser graph for full analysis)
    // -------------------------------------------------------------------------

    /**
     * Build an authoritative call graph from PSI analysis results,
     * supplemented with source-code metadata from the JavaParser parse result.
     * <p>
     * The PSI output provides the complete graph structure (nodes + all edge types
     * including Spring DI, AOP, event, repository edges); the JavaParser
     * {@link ParseResult} provides preferred source metadata where its signatures
     * can be matched. PSI metadata is retained as a fallback, including for Kotlin.
     *
     * @param psiOutput   the complete analysis output from the IntelliJ PSI subprocess
     * @param parseResult the lightweight JavaParser parse result (for metadata enrichment)
     * @return a merged call graph ready for Neo4j storage
     */
    public CallGraph buildCallGraphFromPSI(PSIAnalysisOutput psiOutput, ParseResult parseResult) {
        Map<String, ParsedClass> typeRegistry = buildTypeRegistry(parseResult);
        return buildCallGraphFromPSI(psiOutput, parseResult, typeRegistry.keySet());
    }

    /**
     * Build a call graph from PSI results, filtering to the given set of project class names.
     * <p>
     * When the PSI output covers a multi-module workspace, pass all class names from all modules
     * as {@code globalProjectClassNames} so that cross-module callee nodes are not removed by
     * the third-party filter.  Callers are responsible for further per-module filtering of the
     * returned graph before storage.
     *
     * @param psiOutput               PSI analysis output (may span multiple modules)
     * @param parseResult             JavaParser parse result for the local module
     * @param globalProjectClassNames qualified class names of ALL project-owned classes in
     *                                the workspace; used to distinguish project code from
     *                                third-party/JDK code during filtering
     * @return a merged call graph
     */
    public CallGraph buildCallGraphFromPSI(PSIAnalysisOutput psiOutput, ParseResult parseResult,
                                           Set<String> globalProjectClassNames) {
        return buildCallGraphFromPSI(psiOutput, parseResult, globalProjectClassNames, null);
    }

    /**
     * Build a call graph from PSI results, filtering to the given set of project class names.
     * When {@code globalTypeRegistry} is provided, the JavaParser supplement step uses it to
     * resolve cross-module callee methods (enabling calls from one module to another to be
     * supplemented even when the callee class is not in the local parse result).
     */
    public CallGraph buildCallGraphFromPSI(PSIAnalysisOutput psiOutput, ParseResult parseResult,
                                           Set<String> globalProjectClassNames,
                                           Map<String, ParsedClass> globalTypeRegistry) {
        Set<MethodNode> nodes = new HashSet<>();
        Set<CallEdge> edges = new HashSet<>();

        // 1. Convert PSI method nodes to MethodNode
        Map<String, MethodNode> signatureToNode = new HashMap<>();
        Map<String, SourceMetadata> sourceBySignature = buildSourceMetadata(parseResult);
        Map<String, List<SourceMetadata>> sourceByClassAndMethod = buildSourceMetadataByClassAndMethod(parseResult);
        for (PSIMethodNode psiMethod : psiOutput.methods()) {
            SourceMetadata source = findSourceMetadata(psiMethod, sourceBySignature, sourceByClassAndMethod);
            MethodNode node = new MethodNode(
                psiMethod.qualifiedClassName(),
                psiMethod.methodName(),
                psiMethod.parameterTypes(),
                psiMethod.returnType(),
                psiMethod.modifiers(),
                psiMethod.signature(),
                source != null ? source.sourceFilePath() : psiMethod.sourceFilePath(),
                source != null ? source.sourceCode() : psiMethod.sourceCode()
            );
            nodes.add(node);
            signatureToNode.put(psiMethod.signature(), node);
        }

        // 2. Convert PSI call edges to CallEdge
        for (PSICallEdge psiEdge : psiOutput.edges()) {
            MethodNode caller = signatureToNode.get(psiEdge.callerSignature());
            MethodNode callee = signatureToNode.get(psiEdge.calleeSignature());

            if (callee == null) {
                // SPRING_DI edges use calleeClassName (injected bean class) instead of calleeSignature.
                // Try calleeClassName first, then fall back to calleeSignature.
                String calleeRef = (psiEdge.calleeClassName() != null && !psiEdge.calleeClassName().isBlank())
                        ? psiEdge.calleeClassName()
                        : psiEdge.calleeSignature();
                if (calleeRef != null && !calleeRef.isBlank()) {
                    callee = findOrCreateClassNode(calleeRef, signatureToNode, nodes);
                }
            }

            if (caller != null && callee != null) {
                CallType callType = parseCallType(psiEdge.callType());
                CallEdge edge = psiEdge.lineNumber() >= 0
                    ? new CallEdge(caller, callee, callType, psiEdge.lineNumber())
                    : CallEdge.withoutLine(caller, callee, callType);
                edges.add(edge);
            }
        }

        // 3. Add AOP edges from aspect advice methods to matched target methods
        addAOPEdges(psiOutput.aopBindings(), signatureToNode, nodes, edges);

        // 4. Add event edges from publishers to @EventListener methods
        addEventEdges(psiOutput.eventBindings(), signatureToNode, nodes, edges);

        // 5. Add repository edges from caller methods to repository methods
        addRepositoryEdges(psiOutput.repositoryBindings(), signatureToNode, nodes, edges);

        // 6. Add scheduled edges (scheduler → @Scheduled method)
        addScheduledEdges(psiOutput.scheduledBindings(), signatureToNode, nodes, edges);

        // 7. Add async edges (caller → @Async proxy method)
        addAsyncEdges(psiOutput.asyncBindings(), signatureToNode, nodes, edges);

        // 7b. Resolve INTERFACE edges to concrete implementors using JavaParser type registry
        Map<String, ParsedClass> typeRegistry = buildTypeRegistry(parseResult);
        Map<String, List<String>> inheritanceGraph = buildInheritanceGraph(typeRegistry);
        resolveInterfaceEdges(edges, nodes, signatureToNode, typeRegistry, inheritanceGraph);

        // 8. JavaParser supplementation: for each node, add any method calls that PSI missed.
        //    Unlike a full fallback, this supplements — it adds edges for calls that JavaParser
        //    detected but PSI did not (e.g. static method calls, calls to unresolvable targets).
        //    This runs for every node, not just nodes with zero PSI edges.

        for (MethodNode node : new HashSet<>(nodes)) {
            // Collect callee signatures that PSI already found for this caller
            Set<String> existingCalleeSigs = edges.stream()
                    .filter(e -> e.caller().signature().equals(node.signature()))
                    .map(e -> e.callee().signature())
                    .collect(Collectors.toSet());

            // Look up the class in the local type registry first, then fall back to
            // the global registry for cross-module resolution (e.g. method A in module X
            // calls a static method in module Y).
            Map<String, ParsedClass> resolutionRegistry = globalTypeRegistry != null
                    ? globalTypeRegistry : typeRegistry;
            ParsedClass parsedClass = typeRegistry.get(node.qualifiedClassName());
            if (parsedClass == null) continue;

            // Find the matching ParsedMethod by fully-qualified signature
            ParsedMethod parsedMethod = null;
            for (ParsedMethod m : parsedClass.methods()) {
                if (m.signature().equals(node.signature())) {
                    parsedMethod = m;
                    break;
                }
            }
            if (parsedMethod == null) continue;

            int supplementEdges = 0;
            for (MethodCall methodCall : parsedMethod.methodCalls()) {
                if (methodCall.callType() == CallType.LAMBDA) continue;

                String targetClassName = methodCall.targetClassName();
                ParsedClass targetClass = resolutionRegistry.get(targetClassName);
                if (targetClass == null) continue;

                ParsedMethod targetMethod = findMethod(targetClass, methodCall, resolutionRegistry);
                if (targetMethod == null) continue;

                String calleeSig = targetMethod.signature();

                // Skip if PSI already found this edge
                if (existingCalleeSigs.contains(calleeSig)) continue;

                MethodNode calleeNode = signatureToNode.get(calleeSig);
                if (calleeNode == null) {
                    String modifiers = String.join(" ", targetMethod.modifiers());
                    calleeNode = new MethodNode(
                            targetClass.qualifiedName(), targetMethod.name(),
                            targetMethod.parameterTypes(), targetMethod.returnType(),
                            modifiers, calleeSig,
                            targetClass.sourceFilePath(), targetMethod.sourceCode());
                    nodes.add(calleeNode);
                    signatureToNode.put(calleeSig, calleeNode);
                }
                edges.add(new CallEdge(node, calleeNode, methodCall.callType(), methodCall.lineNumber()));
                supplementEdges++;
            }

            // Final fallback: infer field-based calls from source text when symbol solving misses them.
            if (supplementEdges == 0 && existingCalleeSigs.isEmpty()) {
                supplementEdges += addSourceFieldCallFallback(node, parsedClass, parsedMethod, typeRegistry, signatureToNode, nodes, edges);
            }

            if (supplementEdges > 0) {
                log.debug("JavaParser supplement: {} -> +{} edges for {}", node.signature(), supplementEdges, node.methodName());
            }
        }

        // 9. Filter to project-owned nodes only — remove third-party library nodes (JDK, Spring, etc.)
        //    "project-owned" = qualifiedClassName is in globalProjectClassNames
        int beforeNodes = nodes.size();
        int beforeEdges = edges.size();
        filterToProjectNodes(nodes, edges, globalProjectClassNames);
        log.info("PSI call graph built: {} nodes, {} edges (incl. JavaParser fallback); "
                + "filtered out {} third-party nodes and {} external edges",
                nodes.size(), edges.size(), beforeNodes - nodes.size(), beforeEdges - edges.size());
        return new CallGraph(nodes, edges);
    }

    /**
     * Removes all nodes whose {@code qualifiedClassName} is not in {@code projectClassNames},
     * unless the node has an identifiable source file path (meaning PSI found it in the
     * project sources and it should be retained regardless of JavaParser's type registry).
     * <p>
     * This dual check prevents project-internal classes that JavaParser's source-directory
     * discovery missed from being erroneously removed as "third-party" classes.
     * <p>
     * Exception: well-known virtual source nodes (e.g. the Spring Scheduler trigger node)
     * are always retained so that SCHEDULED / framework-initiated edges are preserved.
     */
    private void filterToProjectNodes(Set<MethodNode> nodes,
                                      Set<CallEdge> edges,
                                      Set<String> projectClassNames) {
        // Virtual nodes added by Octopus itself that must not be filtered out.
        Set<String> virtualRetain = Set.of(
                "org.springframework.scheduling.Scheduler"
        );
        nodes.removeIf(n -> {
            // Keep virtual nodes
            if (virtualRetain.contains(n.qualifiedClassName())) return false;
            // Keep nodes with a source file path (project-owned code found by PSI)
            if (n.sourceFilePath() != null && !n.sourceFilePath().isBlank()) return false;
            // Keep nodes whose class name is in the JavaParser type registry
            if (projectClassNames.contains(n.qualifiedClassName())) return false;
            // Remove — no source file and not in the type registry → third-party / unresolvable
            return true;
        });
        Set<String> retainedSignatures = nodes.stream()
                .map(MethodNode::signature)
                .collect(Collectors.toSet());
        edges.removeIf(e -> !retainedSignatures.contains(e.caller().signature())
                         || !retainedSignatures.contains(e.callee().signature()));
    }

    private int addSourceFieldCallFallback(MethodNode callerNode,
                                           ParsedClass parsedClass,
                                           ParsedMethod parsedMethod,
                                           Map<String, ParsedClass> typeRegistry,
                                           Map<String, MethodNode> signatureToNode,
                                           Set<MethodNode> nodes,
                                           Set<CallEdge> edges) {
        if (parsedMethod.sourceCode() == null || parsedMethod.sourceCode().isBlank()) {
            return 0;
        }

        Map<String, String> fieldTypeByName = new HashMap<>();
        for (ParsedField field : parsedClass.fields()) {
            fieldTypeByName.put(field.name(), field.type());
        }

        // Matches calls like: fieldName.methodName(...)
        Pattern callPattern = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
        Matcher matcher = callPattern.matcher(parsedMethod.sourceCode());

        int added = 0;
        while (matcher.find()) {
            String qualifier = matcher.group(1);
            String targetMethodName = matcher.group(2);

            String fieldType = fieldTypeByName.get(qualifier);
            if (fieldType == null) {
                continue;
            }

            ParsedClass targetClass = resolveParsedClass(fieldType, typeRegistry);
            if (targetClass == null) {
                continue;
            }

            ParsedMethod targetMethod = targetClass.methods().stream()
                    .filter(m -> m.name().equals(targetMethodName))
                    .findFirst()
                    .orElse(null);
            if (targetMethod == null) {
                continue;
            }

            String calleeSig = targetMethod.signature();
            MethodNode calleeNode = signatureToNode.get(calleeSig);
            if (calleeNode == null) {
                String modifiers = String.join(" ", targetMethod.modifiers());
                calleeNode = new MethodNode(
                        targetClass.qualifiedName(),
                        targetMethod.name(),
                        targetMethod.parameterTypes(),
                        targetMethod.returnType(),
                        modifiers,
                        calleeSig,
                        targetClass.sourceFilePath(),
                        targetMethod.sourceCode()
                );
                nodes.add(calleeNode);
                signatureToNode.put(calleeSig, calleeNode);
            }

            if (edges.add(CallEdge.withoutLine(callerNode, calleeNode, CallType.DIRECT))) {
                added++;
            }
        }
        return added;
    }

    private ParsedClass resolveParsedClass(String rawType, Map<String, ParsedClass> typeRegistry) {
        if (rawType == null || rawType.isBlank()) {
            return null;
        }

        String normalized = rawType.replaceAll("<.*?>", "")
                .replace("[]", "")
                .trim();

        ParsedClass direct = typeRegistry.get(normalized);
        if (direct != null) {
            return direct;
        }

        String simple = normalized.contains(".")
                ? normalized.substring(normalized.lastIndexOf('.') + 1)
                : normalized;

        ParsedClass matched = null;
        for (ParsedClass candidate : typeRegistry.values()) {
            if (!candidate.simpleName().equals(simple)) {
                continue;
            }
            if (matched != null) {
                // Ambiguous simple name; avoid wrong edge.
                return null;
            }
            matched = candidate;
        }
        return matched;
    }


    /**
     * For all INTERFACE/SUPER typed edges, try to resolve the callee to its concrete implementing
     * class methods using the inheritance graph built from JavaParser parse results.
     * If implementors are found, the interface edge is replaced by edges to each implementor.
     */
    private void resolveInterfaceEdges(Set<CallEdge> edges,
                                        Set<MethodNode> nodes,
                                        Map<String, MethodNode> signatureToNode,
                                        Map<String, ParsedClass> typeRegistry,
                                        Map<String, List<String>> inheritanceGraph) {
        Set<CallEdge> toRemove = new HashSet<>();
        Set<CallEdge> toAdd = new HashSet<>();

        long interfaceEdgeCount = edges.stream()
                .filter(e -> e.callType() == CallType.INTERFACE || e.callType() == CallType.SUPER)
                .count();
        log.info("resolveInterfaceEdges: total edges={}, interface/super edges={}, inheritanceGraph keys={}",
                edges.size(), interfaceEdgeCount, inheritanceGraph.size());

        for (CallEdge edge : edges) {
            if (edge.callType() != CallType.INTERFACE && edge.callType() != CallType.SUPER) continue;

            MethodNode callee = edge.callee();
            String interfaceClassName = callee.qualifiedClassName();
            String methodName = callee.methodName();
            List<String> paramTypes = callee.parameterTypes();

            // Interfaces may be stored as simple names in the inheritanceGraph (JavaParser limitation)
            String interfaceSimpleName = interfaceClassName.contains(".")
                    ? interfaceClassName.substring(interfaceClassName.lastIndexOf('.') + 1)
                    : interfaceClassName;
            List<String> implementors = inheritanceGraph.getOrDefault(interfaceClassName,
                    inheritanceGraph.getOrDefault(interfaceSimpleName, List.of()));
            log.debug("resolveInterface: {}#{} simpleName={} implementors={}",
                    interfaceClassName, methodName, interfaceSimpleName, implementors);
            boolean resolved = false;
            for (String implClassName : implementors) {
                ParsedClass implClass = typeRegistry.get(implClassName);
                if (implClass == null) {
                    log.info("resolveInterface: implClass not found in typeRegistry: {}", implClassName);
                    continue;
                }

                ParsedMethod implMethod = implClass.methods().stream()
                        .filter(m -> m.name().equals(methodName) && m.parameterTypes().equals(paramTypes))
                        .findFirst().orElse(null);
                if (implMethod == null) {
                    log.info("resolveInterface: no method match in {} for {}({}) - available: {}",
                            implClassName, methodName, paramTypes,
                            implClass.methods().stream().map(m -> m.name() + m.parameterTypes()).toList());
                    // Fallback: match by name only
                    implMethod = implClass.methods().stream()
                            .filter(m -> m.name().equals(methodName))
                            .findFirst().orElse(null);
                }
                if (implMethod == null) continue;

                String implSig = implMethod.signature();
                MethodNode implNode = signatureToNode.get(implSig);
                if (implNode == null) {
                    implNode = new MethodNode(
                            implClass.qualifiedName(), implMethod.name(),
                            implMethod.parameterTypes(), implMethod.returnType(),
                            String.join(" ", implMethod.modifiers()), implSig,
                            implClass.sourceFilePath(), implMethod.sourceCode());
                    nodes.add(implNode);
                    signatureToNode.put(implSig, implNode);
                }
                toAdd.add(new CallEdge(edge.caller(), implNode, edge.callType(), edge.lineNumber()));
                resolved = true;
            }
            if (resolved) {
                toRemove.add(edge);
            }
        }

        edges.removeAll(toRemove);
        edges.addAll(toAdd);
        if (!toRemove.isEmpty()) {
            log.info("Interface resolution: replaced {} interface edges with {} concrete impl edges",
                    toRemove.size(), toAdd.size());
        }
    }

    private MethodNode findOrCreateClassNode(String classOrSignature,
                                              Map<String, MethodNode> existingNodes,
                                              Set<MethodNode> nodes) {
        // Check if we already have a node for this
        MethodNode existing = existingNodes.get(classOrSignature);
        if (existing != null) return existing;

        // Create a placeholder node for the class (used by DI edges)
        String className = classOrSignature;
        String simpleName = className.contains(".")
            ? className.substring(className.lastIndexOf('.') + 1)
            : className;

        MethodNode node = new MethodNode(
            className, simpleName, List.of(), "void", "", classOrSignature, null, null
        );
        nodes.add(node);
        existingNodes.put(classOrSignature, node);
        return node;
    }

    private CallType parseCallType(String callTypeName) {
        try {
            return CallType.valueOf(callTypeName);
        } catch (IllegalArgumentException e) {
            log.debug("Unknown call type '{}', falling back to DIRECT", callTypeName);
            return CallType.DIRECT;
        }
    }

    private void addAOPEdges(List<PSIAOPBinding> aopBindings,
                             Map<String, MethodNode> signatureToNode,
                             Set<MethodNode> nodes, Set<CallEdge> edges) {
        for (PSIAOPBinding binding : aopBindings) {
            String aspectSig = binding.aspectClassName() + "." + binding.aspectMethodName();
            MethodNode aspectNode = resolveByPartialSignature(aspectSig, signatureToNode);
            if (aspectNode == null) continue;

            CallType callType = switch (binding.adviceType()) {
                case "Before" -> CallType.AOP_BEFORE;
                case "Around" -> CallType.AOP_AROUND;
                case "After" -> CallType.AOP_AFTER;
                case "AfterReturning" -> CallType.AOP_AFTER_RETURNING;
                case "AfterThrowing" -> CallType.AOP_AFTER_THROWING;
                default -> CallType.AOP_AROUND;
            };

            for (String targetSig : binding.matchedMethodSignatures()) {
                MethodNode targetNode = signatureToNode.get(targetSig);
                if (targetNode == null) targetNode = resolveByPartialSignature(targetSig, signatureToNode);
                if (targetNode != null) {
                    edges.add(CallEdge.withoutLine(aspectNode, targetNode, callType));
                }
            }
        }
    }

    private void addEventEdges(List<PSIEventBinding> eventBindings,
                               Map<String, MethodNode> signatureToNode,
                               Set<MethodNode> nodes, Set<CallEdge> edges) {
        for (PSIEventBinding binding : eventBindings) {
            for (String publisherSig : binding.publisherMethodSignatures()) {
                MethodNode publisherNode = signatureToNode.get(publisherSig);
                if (publisherNode == null) continue;

                for (String listenerSig : binding.listenerMethodSignatures()) {
                    MethodNode listenerNode = signatureToNode.get(listenerSig);
                    if (listenerNode == null) listenerNode = resolveByPartialSignature(listenerSig, signatureToNode);
                    if (listenerNode != null) {
                        edges.add(CallEdge.withoutLine(publisherNode, listenerNode, CallType.EVENT_PUBLISH));
                    }
                }
            }
        }
    }

    private void addRepositoryEdges(List<PSIRepositoryBinding> repoBindings,
                                    Map<String, MethodNode> signatureToNode,
                                    Set<MethodNode> nodes, Set<CallEdge> edges) {
        for (PSIRepositoryBinding binding : repoBindings) {
            for (String callerSig : binding.callerMethodSignatures()) {
                MethodNode callerNode = signatureToNode.get(callerSig);
                if (callerNode == null) continue;

                for (String repoSig : binding.generatedMethodSignatures()) {
                    MethodNode repoNode = signatureToNode.get(repoSig);
                    if (repoNode == null) {
                        // Create placeholder nodes for repository methods
                        repoNode = findOrCreateClassNode(repoSig, signatureToNode, nodes);
                    }
                    edges.add(CallEdge.withoutLine(callerNode, repoNode, CallType.REPOSITORY));
                }
            }
        }
    }

    private void addScheduledEdges(List<PSIScheduledBinding> scheduledBindings,
                                   Map<String, MethodNode> signatureToNode,
                                   Set<MethodNode> nodes, Set<CallEdge> edges) {
        // Create a virtual "Scheduler" node as the source
        MethodNode schedulerNode = new MethodNode(
            "org.springframework.scheduling.Scheduler",
            "schedule",
            List.of(), "void", "", "org.springframework.scheduling.Scheduler.schedule():void", null, null
        );
        nodes.add(schedulerNode);

        for (PSIScheduledBinding binding : scheduledBindings) {
            MethodNode targetNode = signatureToNode.get(binding.methodSignature());
            if (targetNode == null) {
                targetNode = resolveByPartialSignature(binding.methodSignature(), signatureToNode);
            }
            if (targetNode != null) {
                edges.add(CallEdge.withoutLine(schedulerNode, targetNode, CallType.SCHEDULED));
            }
        }
    }

    private void addAsyncEdges(List<PSIAsyncBinding> asyncBindings,
                               Map<String, MethodNode> signatureToNode,
                               Set<MethodNode> nodes, Set<CallEdge> edges) {
        for (PSIAsyncBinding binding : asyncBindings) {
            MethodNode asyncNode = signatureToNode.get(binding.asyncMethodSignature());
            if (asyncNode == null) continue;

            for (String callerSig : binding.callerMethodSignatures()) {
                MethodNode callerNode = signatureToNode.get(callerSig);
                if (callerNode != null) {
                    edges.add(CallEdge.withoutLine(callerNode, asyncNode, CallType.ASYNC));
                }
            }
        }
    }

    /**
     * Try to find a method node by partial signature matching.
     * Handles cases where the PSI output may use slightly different type formatting.
     */
    private MethodNode resolveByPartialSignature(String partialSig,
                                                  Map<String, MethodNode> signatureToNode) {
        // Exact match
        MethodNode exact = signatureToNode.get(partialSig);
        if (exact != null) return exact;

        // Try prefix match (class.method name part)
        String methodPart = partialSig.contains("(") ? partialSig.substring(0, partialSig.indexOf('(')) : partialSig;
        for (Map.Entry<String, MethodNode> entry : signatureToNode.entrySet()) {
            if (entry.getKey().startsWith(methodPart)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
