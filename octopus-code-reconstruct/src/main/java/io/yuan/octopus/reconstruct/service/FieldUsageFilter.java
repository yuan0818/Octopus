package io.yuan.octopus.reconstruct.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Filters fields to only those referenced by the involved methods' source code.
 * Uses regex matching of field names in method bodies.
 */
@Component
public class FieldUsageFilter {

    /**
     * Returns only fields that are referenced in at least one of the given method source codes.
     * <ul>
     *   <li>{@code static final} constants are always retained (compile-time constants may not appear in source).</li>
     *   <li>Fields whose name appears as a standalone word in the source code are considered referenced.</li>
     * </ul>
     *
     * @param allFields    all fields of the class
     * @param methodSourceCodes source code of the methods that are in the call chain
     * @return filtered field list
     */
    public List<FieldEntry> filter(List<FieldEntry> allFields, List<String> methodSourceCodes) {
        if (!methodSourceCodes.isEmpty()) {
            String combinedSource = String.join("\n", methodSourceCodes);
            return allFields.stream()
                .filter(f -> isReferenced(f, combinedSource))
                .collect(Collectors.toList());
        }
        return allFields;
    }

    private boolean isReferenced(FieldEntry field, String source) {
        // Constants always retain
        String mod = field.modifiers() != null ? field.modifiers().replace(",", " ").replaceAll("\\s+", " ") : "";
        if (mod.contains("static final") || mod.contains("final static")) {
            return true;
        }
        // Spring injection fields always retain
        if (field.hasInjectAnnotation()) {
            return true;
        }
        // Check if field name appears as a word boundary match in source
        Pattern p = Pattern.compile("\\b" + Pattern.quote(field.fieldName()) + "\\b");
        return p.matcher(source).find();
    }

    /**
     * Lightweight field descriptor used during reconstruction.
     */
    public record FieldEntry(
        String fieldName,
        String fieldType,
        String modifiers,
        String annotationParams
    ) {
        public boolean hasInjectAnnotation() {
            return annotationParams != null && (
                annotationParams.contains("Autowired") ||
                annotationParams.contains("Resource") ||
                annotationParams.contains("Inject")
            );
        }
    }
}
