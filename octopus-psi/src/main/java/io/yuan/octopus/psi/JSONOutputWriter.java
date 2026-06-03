package io.yuan.octopus.psi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the PSI analysis results to a JSON file that the Octopus main
 * module can deserialize into {@code PSIAnalysisOutput}.
 */
public class JSONOutputWriter {

    private final Gson gson = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    /**
     * Write the complete analysis result as a single JSON file.
     */
    public void write(Path outputPath,
                      CallGraphResult callGraph,
                      List<Map<String, Object>> beanDefinitions,
                      List<Map<String, Object>> aopBindings,
                      List<Map<String, Object>> eventBindings,
                      List<Map<String, Object>> scheduledBindings,
                      List<Map<String, Object>> asyncBindings,
                      List<Map<String, Object>> repositoryBindings) throws IOException {

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("methods", callGraph.methods());
        root.put("edges", callGraph.edges());
        root.put("beans", beanDefinitions);
        root.put("aopBindings", aopBindings);
        root.put("eventBindings", eventBindings);
        root.put("scheduledBindings", scheduledBindings);
        root.put("asyncBindings", asyncBindings);
        root.put("repositoryBindings", repositoryBindings);

        String json = gson.toJson(root);
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        Files.writeString(outputPath, json, StandardCharsets.UTF_8);
    }
}
