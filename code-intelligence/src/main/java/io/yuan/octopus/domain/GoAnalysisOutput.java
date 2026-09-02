package io.yuan.octopus.domain;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Deserialized output of the octopus go-project-analyzer HTTP service
 * ({@code POST /analyze}), mirroring the Go structs in
 * {@code go-project-analyzer/analyzer.go}.
 *
 * <p>Signatures are produced solely by the Go side
 * ({@code <pkgPath[.Type]>.<name>(<paramTypes>):<returnTypes>}), so
 * declaration nodes and call edges always agree.
 */
public record GoAnalysisOutput(
        List<GoTypeSpec> types,
        List<GoMethodNode> methods,
        List<GoCallEdge> edges,
        List<GoHttpRoute> httpRoutes
) {

    /** A named struct or interface type (maps to class_info). */
    public record GoTypeSpec(
            String qualifiedName,
            String name,
            /** "struct" | "interface". */
            String kind,
            String packagePath,
            String file,
            int lineStart,
            int lineEnd,
            /** Qualified name of the first embedded base struct, "" if none. */
            String embedded,
            @SerializedName("implements") List<String> implementedInterfaces,
            List<GoField> fields
    ) {}

    public record GoField(String name, String type) {}

    /** A function or method declaration (maps to MethodNode + method_info). */
    public record GoMethodNode(
            String qualifiedClassName,
            String methodName,
            String signature,
            List<String> parameterTypes,
            List<String> returnTypes,
            String modifiers,
            String sourceFilePath,
            String sourceCode,
            int lineStart,
            int lineEnd
    ) {}

    /** A workspace-internal call edge (stdlib/third-party edges are dropped Go-side). */
    public record GoCallEdge(
            String callerSignature,
            String calleeSignature,
            /** "DIRECT" | "INTERFACE". */
            String callType,
            int lineNumber
    ) {}

    /**
     * An HTTP route registration detected by the Go analyzer (net/http mux,
     * gin, echo, chi). Stored as a synthetic {@code HttpRoute} annotation on
     * the handler method so entry-point queries display the API path.
     */
    public record GoHttpRoute(
            String path,
            String method,
            String handlerSignature,
            int lineNumber
    ) {}
}
