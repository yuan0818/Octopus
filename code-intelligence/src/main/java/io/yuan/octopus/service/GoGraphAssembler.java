package io.yuan.octopus.service;

import io.yuan.octopus.common.CallType;
import io.yuan.octopus.common.ClassType;
import io.yuan.octopus.domain.CallEdge;
import io.yuan.octopus.domain.CallGraph;
import io.yuan.octopus.domain.GoAnalysisOutput;
import io.yuan.octopus.domain.GoAnalysisOutput.GoCallEdge;
import io.yuan.octopus.domain.GoAnalysisOutput.GoMethodNode;
import io.yuan.octopus.domain.GoAnalysisOutput.GoTypeSpec;
import io.yuan.octopus.domain.MethodNode;
import io.yuan.octopus.domain.ParseError;
import io.yuan.octopus.domain.ParseResult;
import io.yuan.octopus.domain.ParsedClass;
import io.yuan.octopus.domain.ParsedField;
import io.yuan.octopus.domain.ParsedFile;
import io.yuan.octopus.domain.ParsedMethod;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts the go-project-analyzer JSON payload into the language-neutral
 * graph/metadata shapes consumed by {@link GraphStoreService} and
 * {@link MetadataStoreService}.
 *
 * <p>Mapping conventions (Go → Octopus):
 * <ul>
 *   <li>Named struct/interface → class_info row ({@code pkgPath.TypeName})</li>
 *   <li>Package with free functions → pseudo-class ({@code pkgPath}) so
 *       functions land in method_info</li>
 *   <li>Interface satisfaction → implements rows (computed Go-side via
 *       types.Implements)</li>
 *   <li>callType "DIRECT"/"INTERFACE" map to the existing {@link CallType}
 *       values; interface dispatch is already fanned out Go-side</li>
 * </ul>
 */
@Service
public class GoGraphAssembler {

    // ── Graph assembly ──────────────────────────────────────────────────────

    public CallGraph buildCallGraph(GoAnalysisOutput output) {
        Map<String, MethodNode> nodesBySignature = new LinkedHashMap<>();
        for (GoMethodNode m : output.methods()) {
            nodesBySignature.put(m.signature(), toMethodNode(m));
        }

        Set<CallEdge> edges = new HashSet<>();
        Set<MethodNode> nodes = new HashSet<>(nodesBySignature.values());
        for (GoCallEdge e : output.edges()) {
            MethodNode caller = nodesBySignature.get(e.callerSignature());
            MethodNode callee = nodesBySignature.get(e.calleeSignature());
            if (caller == null || callee == null) {
                continue;
            }
            edges.add(new CallEdge(caller, callee, toCallType(e.callType()), e.lineNumber()));
        }
        return new CallGraph(nodes, edges);
    }

    private MethodNode toMethodNode(GoMethodNode m) {
        return new MethodNode(
                m.qualifiedClassName(),
                m.methodName(),
                m.parameterTypes() != null ? m.parameterTypes() : List.of(),
                String.join(", ", m.returnTypes() != null ? m.returnTypes() : List.of()),
                m.modifiers() != null ? m.modifiers() : "",
                m.signature(),
                m.sourceFilePath(),
                m.sourceCode() != null ? m.sourceCode() : ""
        );
    }

    private CallType toCallType(String callType) {
        return "INTERFACE".equals(callType) ? CallType.INTERFACE : CallType.DIRECT;
    }

    // ── Metadata assembly ───────────────────────────────────────────────────

    public ParseResult buildParseResult(GoAnalysisOutput output) {
        // Methods grouped by their declaring "class": pkgPath.Type for methods,
        // pkgPath pseudo-class for free functions.
        Map<String, List<GoMethodNode>> methodsByClass = new LinkedHashMap<>();
        for (GoMethodNode m : output.methods()) {
            methodsByClass.computeIfAbsent(m.qualifiedClassName(), k -> new ArrayList<>()).add(m);
        }

        // Types keyed by qualified name for method attachment.
        Map<String, GoTypeSpec> typesByName = new HashMap<>();
        for (GoTypeSpec t : output.types()) {
            typesByName.put(t.qualifiedName(), t);
        }

        // HTTP routes keyed by handler signature for synthetic annotations.
        Map<String, List<GoAnalysisOutput.GoHttpRoute>> routesByHandler = new HashMap<>();
        if (output.httpRoutes() != null) {
            for (GoAnalysisOutput.GoHttpRoute r : output.httpRoutes()) {
                routesByHandler.computeIfAbsent(r.handlerSignature(), k -> new ArrayList<>()).add(r);
            }
        }

        // One ParsedClass per named type; plus pseudo-classes for packages
        // holding free functions.
        Map<String, ParsedClass> classByQName = new LinkedHashMap<>();
        for (GoTypeSpec t : output.types()) {
            List<GoMethodNode> methods = methodsByClass.get(t.qualifiedName());
            classByQName.put(t.qualifiedName(), toParsedClass(t, methods, routesByHandler));
        }
        for (Map.Entry<String, List<GoMethodNode>> entry : methodsByClass.entrySet()) {
            String qName = entry.getKey();
            if (typesByName.containsKey(qName)) {
                continue; // methods of a named type — attached to its class
            }
            // qName is a bare package path: pseudo-class carrying free functions.
            String simpleName = qName.substring(qName.lastIndexOf('/') + 1);
            ParsedClass pseudo = new ParsedClass(
                    qName, simpleName, ClassType.CLASS, List.of(),
                    null, List.of(), toParsedMethods(entry.getValue(), routesByHandler), List.of(), List.of(),
                    entry.getValue().isEmpty() ? "" : nullToEmpty(entry.getValue().get(0).sourceFilePath()),
                    List.of());
            classByQName.put(qName, pseudo);
        }

        // Group classes into ParsedFiles by source path so package rows and
        // diff filtering work like the JavaParser path.
        Map<String, List<ParsedClass>> classesByFile = new LinkedHashMap<>();
        Map<String, String> filePackage = new LinkedHashMap<>();
        for (ParsedClass c : classByQName.values()) {
            String file = c.sourceFilePath() != null ? c.sourceFilePath() : "";
            classesByFile.computeIfAbsent(file, k -> new ArrayList<>()).add(c);
            if (!file.isEmpty()) {
                filePackage.putIfAbsent(file, packageNameOf(c.qualifiedName()));
            }
        }

        List<ParsedFile> files = new ArrayList<>();
        for (Map.Entry<String, List<ParsedClass>> entry : classesByFile.entrySet()) {
            files.add(new ParsedFile(entry.getKey(), filePackage.getOrDefault(entry.getKey(), ""),
                    List.of(), entry.getValue()));
        }
        return new ParseResult(files, List.of());
    }

    private ParsedClass toParsedClass(GoTypeSpec t, List<GoMethodNode> methods,
                                      Map<String, List<GoAnalysisOutput.GoHttpRoute>> routesByHandler) {
        return new ParsedClass(
                t.qualifiedName(),
                t.name(),
                "interface".equals(t.kind()) ? ClassType.INTERFACE : ClassType.CLASS,
                List.of(),
                t.embedded() != null && !t.embedded().isEmpty() ? t.embedded() : null,
                t.implementedInterfaces() != null ? t.implementedInterfaces() : List.of(),
                methods != null ? toParsedMethods(methods, routesByHandler) : List.of(),
                toParsedFields(t),
                List.of(),
                t.file(),
                List.of()
        );
    }

    private List<ParsedField> toParsedFields(GoTypeSpec t) {
        if (t.fields() == null) return List.of();
        List<ParsedField> out = new ArrayList<>();
        for (var f : t.fields()) {
            out.add(new ParsedField(f.name(), f.type(), List.of(), List.of()));
        }
        return out;
    }

    private List<ParsedMethod> toParsedMethods(List<GoMethodNode> methods) {
        return toParsedMethods(methods, Map.of());
    }

    /**
     * @param routesByHandler HTTP routes keyed by handler signature; each is
     *                        attached as a synthetic {@code HttpRoute} annotation
     *                        so entry-point queries display the API path
     */
    private List<ParsedMethod> toParsedMethods(List<GoMethodNode> methods,
                                               Map<String, List<GoAnalysisOutput.GoHttpRoute>> routesByHandler) {
        List<ParsedMethod> out = new ArrayList<>(methods.size());
        for (GoMethodNode m : methods) {
            List<io.yuan.octopus.domain.ParsedAnnotation> annotations = List.of();
            List<GoAnalysisOutput.GoHttpRoute> routes = routesByHandler.get(m.signature());
            if (routes != null && !routes.isEmpty()) {
                annotations = new ArrayList<>(routes.size());
                for (GoAnalysisOutput.GoHttpRoute r : routes) {
                    Map<String, String> params = new LinkedHashMap<>();
                    params.put("path", r.path());
                    params.put("method", r.method());
                    annotations.add(new io.yuan.octopus.domain.ParsedAnnotation("HttpRoute", params));
                }
            }
            out.add(new ParsedMethod(
                    m.methodName(),
                    m.signature(),
                    m.parameterTypes() != null ? m.parameterTypes() : List.of(),
                    String.join(", ", m.returnTypes() != null ? m.returnTypes() : List.of()),
                    List.of(),
                    m.sourceCode() != null ? m.sourceCode() : "",
                    List.of(),   // methodCalls: graph edges carry this information
                    annotations,
                    m.lineStart() > 0 ? m.lineStart() : null,
                    m.lineEnd() > 0 ? m.lineEnd() : null
            ));
        }
        return out;
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private static String packageNameOf(String qualifiedName) {
        int idx = qualifiedName.lastIndexOf('.');
        return idx > 0 ? qualifiedName.substring(0, idx) : qualifiedName;
    }
}
