package io.yuan.octopus.reconstruct.service;

import io.yuan.octopus.reconstruct.dto.ReconstructRequest;
import io.yuan.octopus.reconstruct.dto.ReconstructResponse;
import io.yuan.octopus.reconstruct.service.CallChainCollector.CallChainResult;
import io.yuan.octopus.reconstruct.service.ClassSourceAssembler.AssembledClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Orchestrates the full reconstruct pipeline:
 * 1. Collect call chain via {@link CallChainCollector}
 * 2. Assemble source per class via {@link ClassSourceAssembler}
 * 3. Build response
 */
@Service
public class ReconstructService {

    private static final Logger log = LoggerFactory.getLogger(ReconstructService.class);

    private static final Pattern QUALIFIED_NAME_PATTERN =
            Pattern.compile("\\b[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+\\.[A-Z][a-zA-Z0-9_]*\\b");

    private final CallChainCollector chainCollector;
    private final ClassSourceAssembler sourceAssembler;
    private final DataSource dataSource;
    private final int maxClasses;

    public ReconstructService(CallChainCollector chainCollector,
                              ClassSourceAssembler sourceAssembler,
                              DataSource dataSource,
                              @Value("${reconstruct.max-classes:50}") int maxClasses) {
        this.chainCollector = chainCollector;
        this.sourceAssembler = sourceAssembler;
        this.dataSource = dataSource;
        this.maxClasses = maxClasses;
    }

    public ReconstructResponse reconstruct(ReconstructRequest request) {
        long start = System.currentTimeMillis();
        Long projectId = request.projectId();
        String strategy = request.getStrategy();

        // Step 1: collect downstream call chain
        CallChainResult chain = chainCollector.collect(
            projectId, request.className(), request.methodName(), strategy);

        // Step 2: assemble source for each class (at minimum the anchor class)
        Map<String, String> classSources = new LinkedHashMap<>();
        boolean truncated = false;
        int totalMethods = 0;
        int totalChars = 0;

        // Determine which classes to process: include ALL involved classes, even those
        // without resolved method signatures in classToSigs (e.g. service classes called
        // by the controller). For classes without specific signatures, assemble full source.
        Set<String> classesToProcessSet = new LinkedHashSet<>();
        classesToProcessSet.addAll(chain.involvedClasses());          // e.g. DialController + DialService
        if (classesToProcessSet.isEmpty()) {
            classesToProcessSet.add(request.className());             // fallback: anchor only
        }

        List<String> classesToProcess = new ArrayList<>(classesToProcessSet);
        // Anchor first
        classesToProcess.remove(request.className());
        classesToProcess.add(0, request.className());

        int processed = 0;
        for (String clsName : classesToProcess) {
            if (processed >= maxClasses) { truncated = true; break; }
            Set<String> sigs = chain.classToSigs().getOrDefault(clsName, Set.of());
            if (sigs == null || sigs.isEmpty()) {
                // No specific signatures for this class (anchor with no chain data).
                // Still try to assemble the full class source.
                try {
                    AssembledClass assembled = sourceAssembler.assemble(projectId, clsName, null);
                    if (assembled != null) {
                        classSources.put(clsName, assembled.sourceCode());
                        totalChars += assembled.sourceCode().length();
                        processed++;
                    }
                } catch (Exception e) {
                    log.warn("Failed to assemble class {}: {}", clsName, e.getMessage());
                }
                continue;
            }

            try {
                AssembledClass assembled = sourceAssembler.assemble(projectId, clsName, sigs);
                if (assembled != null) {
                    classSources.put(clsName, assembled.sourceCode());
                    totalMethods += sigs.size();
                    totalChars += assembled.sourceCode().length();
                    processed++;
                }
            } catch (Exception e) {
                log.warn("Failed to assemble class {}: {}", clsName, e.getMessage());
            }
        }

        // Step 3: expand referenced types — scan assembled source for FQN references
        // that belong to this project but are not yet in classSources (e.g. Mapper
        // interfaces, dependent entity classes), and try to assemble them too.
        List<Map.Entry<String, String>> expanded = expandReferencedTypes(projectId, classSources, maxClasses - classSources.size());
        for (Map.Entry<String, String> entry : expanded) {
            classSources.putIfAbsent(entry.getKey(), entry.getValue());
            totalChars += entry.getValue().length();
        }
        if (!expanded.isEmpty()) {
            log.info("Expanded {} additional referenced types", expanded.size());
        }

        // If we still got nothing, then truly no data is available.
        if (classSources.isEmpty()) {
            throw new NoSuchElementException("No source data found for "
                + request.className() + "." + request.methodName());
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Reconstructed {} classes / {} methods in {}ms for {}.{}",
            classSources.size(), totalMethods, elapsed,
            request.className(), request.methodName());

        return new ReconstructResponse(
            request.className() + "." + request.methodName(),
            classSources,
            new ArrayList<>(classSources.keySet()),
            totalMethods,
            totalChars,
            truncated
        );
    }

    /**
     * Collect all project IDs that belong to the same repo as the anchor project.
     * For local analyses, all modules analysed in the same task share a commit_id.
     * For git analyses, they share a repo_config_id.
     */
    private List<Long> findRepoProjectIds(Long anchorProjectId) {
        List<Long> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM java_project WHERE (repo_config_id IS NOT NULL AND repo_config_id = "
                + "(SELECT repo_config_id FROM java_project WHERE id = ?)) "
                + "OR (repo_config_id IS NULL AND commit_id IS NOT NULL AND commit_id = "
                + "(SELECT commit_id FROM java_project WHERE id = ?))")) {
            ps.setLong(1, anchorProjectId);
            ps.setLong(2, anchorProjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong("id"));
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to find repo project ids: {}", e.getMessage());
        }
        if (ids.isEmpty()) ids.add(anchorProjectId);
        return ids;
    }

    /**
     * Scan ALL imports in the assembled sources.  For each import whose
     * qualified name exists in ANY module of this repo (sharing the same
     * workspace root), try to assemble it.  This picks up cross-module
     * dependencies like CopyUtils in component-common for a controller
     * in nothing-watch-device-server.
     */
    private List<Map.Entry<String, String>> expandReferencedTypes(
            Long projectId, Map<String, String> existingSources, int budget) {
        if (budget <= 0) return List.of();

        // Collect all project IDs in this repo
        List<Long> repoProjectIds = findRepoProjectIds(projectId);

        // Collect ALL import FQN from assembled sources (excluding java.* etc.)
        Set<String> importRefs = new LinkedHashSet<>();
        for (String source : existingSources.values()) {
            Matcher m = Pattern.compile("^import\\s+([a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+\\.[A-Z][a-zA-Z0-9_]+);\\s*$", Pattern.MULTILINE).matcher(source);
            while (m.find()) {
                String fqn = m.group(1);
                // Skip framework/JDK types
                if (fqn.startsWith("java.") || fqn.startsWith("javax.")
                    || fqn.startsWith("jakarta.") || fqn.startsWith("org.slf4j")
                    || fqn.startsWith("org.springframework") || fqn.startsWith("org.apache")
                    || fqn.startsWith("com.fasterxml") || fqn.startsWith("com.baomidou")
                    || fqn.startsWith("lombok") || fqn.startsWith("io.swagger")
                    || fqn.startsWith("org.redisson") || fqn.startsWith("com.github")) continue;
                importRefs.add(fqn);
            }
        }

        // Remove already-included FQN
        importRefs.removeAll(existingSources.keySet());

        if (importRefs.isEmpty()) return List.of();

        // Check which ones exist in ANY module of this repo
        String placeholders = importRefs.stream().map(r -> "?").collect(Collectors.joining(","));
        Map<String, Long> available = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT qualified_name, project_id FROM class_info "
                + "WHERE project_id IN (" + repoProjectIds.stream().map(r -> "?").collect(Collectors.joining(",")) + ")"
                + " AND qualified_name IN (" + placeholders + ")")) {
            int idx = 1;
            for (Long pid : repoProjectIds) {
                ps.setLong(idx++, pid);
            }
            for (String ref : importRefs) {
                ps.setString(idx++, ref);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    available.put(rs.getString("qualified_name"), rs.getLong("project_id"));
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to query referenced types: {}", e.getMessage());
            return List.of();
        }

        // Assemble each found type using its own projectId
        List<Map.Entry<String, String>> result = new ArrayList<>();
        int count = 0;
        for (Map.Entry<String, Long> entry : available.entrySet()) {
            if (count >= budget) break;
            try {
                AssembledClass assembled = sourceAssembler.assemble(entry.getValue(), entry.getKey(), null);
                if (assembled != null) {
                    result.add(Map.entry(entry.getKey(), assembled.sourceCode()));
                    count++;
                }
            } catch (Exception e) {
                log.warn("Failed to assemble referenced type {}: {}", entry.getKey(), e.getMessage());
            }
        }
        return result;
    }
}
