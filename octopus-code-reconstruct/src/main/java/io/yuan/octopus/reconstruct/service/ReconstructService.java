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
 * 2. Assemble source per class via {@link ClassSourceAssembler} (MySQL) or {@link GitSourceProvider} (JGit)
 * 3. Build response
 *
 * <p>源码获取优先级：
 * <ol>
 *   <li>若 request 中指定了 {@code commitId}，优先通过 {@link GitSourceProvider} 从 git 读取</li>
 *   <li>JGit 失败时回退到 {@link ClassSourceAssembler} 的 MySQL source_code 缓存</li>
 *   <li>结果中 {@code sourceOrigin} 字段标注源码来源（"git" 或 "mysql_fallback"）</li>
 * </ol>
 */
@Service
public class ReconstructService {

    private static final Logger log = LoggerFactory.getLogger(ReconstructService.class);

    private static final Pattern QUALIFIED_NAME_PATTERN =
            Pattern.compile("\\b[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+\\.[A-Z][a-zA-Z0-9_]*\\b");

    private final CallChainCollector chainCollector;
    private final ClassSourceAssembler sourceAssembler;
    private final GitSourceProvider gitSourceProvider;
    private final DataSource dataSource;
    private final int maxClasses;

    public ReconstructService(CallChainCollector chainCollector,
                              ClassSourceAssembler sourceAssembler,
                              GitSourceProvider gitSourceProvider,
                              DataSource dataSource,
                              @Value("${reconstruct.max-classes:50}") int maxClasses) {
        this.chainCollector = chainCollector;
        this.sourceAssembler = sourceAssembler;
        this.gitSourceProvider = gitSourceProvider;
        this.dataSource = dataSource;
        this.maxClasses = maxClasses;
    }

    public ReconstructResponse reconstruct(ReconstructRequest request) {
        long start = System.currentTimeMillis();
        Long projectId = request.projectId();
        String strategy = request.getStrategy();
        String commitId = request.commitId();

        // Step 1: collect downstream call chain（branch 限定分支，commitId 限定版本）
        CallChainResult chain = chainCollector.collect(
            projectId, request.className(), request.methodName(), strategy, request.branch(), commitId);

        // Step 2: assemble source for each class
        Map<String, String> classSources = new LinkedHashMap<>();
        boolean truncated = false;
        int totalMethods = 0;
        int totalChars = 0;
        boolean anyFallback = false;

        // Determine which classes to process
        Set<String> classesToProcessSet = new LinkedHashSet<>();
        classesToProcessSet.addAll(chain.involvedClasses());
        if (classesToProcessSet.isEmpty()) {
            classesToProcessSet.add(request.className());
        }

        List<String> classesToProcess = new ArrayList<>(classesToProcessSet);
        classesToProcess.remove(request.className());
        classesToProcess.add(0, request.className());

        int processed = 0;
        for (String clsName : classesToProcess) {
            if (processed >= maxClasses) { truncated = true; break; }
            Set<String> sigs = chain.classToSigs().getOrDefault(clsName, Set.of());

            // Assemble the class source (try JGit first, fallback to MySQL)
            SourceAssembly assembled = assembleClass(projectId, commitId, clsName, sigs);
            if (assembled != null) {
                classSources.put(clsName, assembled.sourceCode());
                totalChars += assembled.sourceCode().length();
                if (!assembled.fromGit()) {
                    anyFallback = true;
                }
                if (!sigs.isEmpty()) {
                    totalMethods += sigs.size();
                }
                processed++;
            }
        }

        // Step 3: expand referenced types
        List<Map.Entry<String, String>> expanded = expandReferencedTypes(projectId, classSources, maxClasses - classSources.size());
        for (Map.Entry<String, String> entry : expanded) {
            classSources.putIfAbsent(entry.getKey(), entry.getValue());
            totalChars += entry.getValue().length();
        }
        if (!expanded.isEmpty()) {
            log.info("Expanded {} additional referenced types", expanded.size());
        }

        if (classSources.isEmpty()) {
            throw new NoSuchElementException("No source data found for "
                + request.className() + "." + request.methodName());
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Reconstructed {} classes / {} methods in {}ms for {}.{} (source={})",
            classSources.size(), totalMethods, elapsed,
            request.className(), request.methodName(), anyFallback ? "mysql_fallback" : "git");

        return new ReconstructResponse(
            request.className() + "." + request.methodName(),
            classSources,
            new ArrayList<>(classSources.keySet()),
            totalMethods,
            totalChars,
            truncated,
            anyFallback ? "mysql_fallback" : "git"
        );
    }

    /**
     * 组装单个类的源码：优先 JGit git show，失败时回退 MySQL 缓存。
     *
     * @param projectId 项目 ID
     * @param commitId  commit hash（可选）
     * @param className 类全限定名
     * @param sigs      该类中需要提取的方法签名集合（为空时试取完整类源码）
     * @return 组装结果（null 表示不能组装）
     */
    private SourceAssembly assembleClass(Long projectId, String commitId,
                                          String className, Set<String> sigs) {
        // 如果指定了 commitId，优先通过 JGit 获取源码
        if (commitId != null && !commitId.isBlank()) {
            SourceAssembly jitResult = assembleFromJGit(projectId, commitId, className, sigs);
            if (jitResult != null) {
                return jitResult;
            }
        }

        // 回退：MySQL ClassSourceAssembler（source_code 缓存）
        try {
            AssembledClass mysqlResult = sourceAssembler.assemble(projectId, className, sigs);
            if (mysqlResult != null) {
                log.debug("Fallback to MySQL source for {} (project={})", className, projectId);
                return new SourceAssembly(mysqlResult.sourceCode(), false);
            }
        } catch (Exception e) {
            log.warn("MySQL source fallback failed for {}: {}", className, e.getMessage());
        }

        return null;
    }

    /**
     * 通过 JGit git show 按行号提取方法体，组装成类源码。
     */
    private SourceAssembly assembleFromJGit(Long projectId, String commitId,
                                             String className, Set<String> sigs) {
        try {
            // 查询该类的 source_file_path 以及每个方法的 file_path / line_start / line_end。
            // 版本一致性约束：
            //   1) class 行精确匹配 commit_id——不允许静默回退 HEAD 行（否则 HEAD 行号
            //      配上指定 commit 的文件内容会提取错方法体）；查不到时返回 null 走 MySQL 回退。
            //   2) method 行跟随 class 行的 commit_id（method_info 按 (class_id, commit_id)
            //      多版本存储，不限定版本会取到任意版本的方法行号）。
            String sql = """
                SELECT ci.source_file_path AS class_file_path,
                       mi.file_path, mi.line_start, mi.line_end, mi.method_signature
                FROM class_info ci
                LEFT JOIN method_info mi ON mi.class_id = ci.id AND mi.commit_id = ci.commit_id
                WHERE ci.project_id = ?
                  AND ci.qualified_name = ?
                  AND ci.commit_id = ?
                """;
            String classFilePath = null;
            Map<String, MethodLineRange> methodRanges = new LinkedHashMap<>();

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, projectId);
                ps.setString(2, className);
                ps.setString(3, commitId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (classFilePath == null) {
                            classFilePath = rs.getString("class_file_path");
                        }
                        String sig = rs.getString("method_signature");
                        String filePath = rs.getString("file_path");
                        int lineStart = rs.getInt("line_start");
                        int lineEnd = rs.getInt("line_end");
                        if (sig != null && lineStart > 0) {
                            methodRanges.put(sig, new MethodLineRange(
                                filePath != null ? filePath : classFilePath,
                                lineStart, lineEnd));
                        }
                    }
                }
            }

            if (classFilePath == null) {
                // 指定 commit 无该类元数据：不混用 HEAD 版本，交由调用方回退 MySQL
                log.debug("No class_info row for {} at commit {} (project={}); falling back to MySQL",
                        className, commitId, projectId);
                return null;
            }

            // 读取完整文件内容
            SourceResult fileResult = gitSourceProvider.getSource(
                projectId, commitId, classFilePath, 1, Integer.MAX_VALUE);
            if (!fileResult.found()) {
                return null;
            }

            // 如果有签名列表，按行号提取每个方法；否则返回完整类内容
            if (sigs != null && !sigs.isEmpty()) {
                StringBuilder source = new StringBuilder();
                for (String sig : sigs) {
                    MethodLineRange range = methodRanges.get(sig);
                    if (range != null && range.lineEnd() > 0) {
                        String methodSource = gitSourceProvider.extractLines(
                            fileResult.content(), range.lineStart(), range.lineEnd());
                        source.append(methodSource).append("\n\n");
                    }
                }
                String assembled = source.toString().trim();
                return assembled.isEmpty() ? null : new SourceAssembly(assembled, true);
            }

            return new SourceAssembly(fileResult.content(), true);

        } catch (Exception e) {
            log.debug("JGit assembly failed for {} (will fallback): {}", className, e.getMessage());
            return null;
        }
    }

    // ── 原有逻辑：跨模块引用扩展 ────────────────────────────────────

    private List<Long> findRepoProjectIds(Long anchorProjectId) {
        List<Long> ids = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM analyzed_project WHERE (repo_config_id IS NOT NULL AND repo_config_id = "
                + "(SELECT repo_config_id FROM analyzed_project WHERE id = ?)) "
                + "OR (repo_config_id IS NULL AND commit_id IS NOT NULL AND commit_id = "
                + "(SELECT commit_id FROM analyzed_project WHERE id = ?))")) {
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

    private List<Map.Entry<String, String>> expandReferencedTypes(
            Long projectId, Map<String, String> existingSources, int budget) {
        if (budget <= 0) return List.of();

        List<Long> repoProjectIds = findRepoProjectIds(projectId);

        Set<String> importRefs = new LinkedHashSet<>();
        for (String source : existingSources.values()) {
            Matcher m = Pattern.compile("^import\\s+([a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+\\.[A-Z][a-zA-Z0-9_]+);\\s*$", Pattern.MULTILINE).matcher(source);
            while (m.find()) {
                String fqn = m.group(1);
                if (fqn.startsWith("java.") || fqn.startsWith("javax.")
                    || fqn.startsWith("jakarta.") || fqn.startsWith("org.slf4j")
                    || fqn.startsWith("org.springframework") || fqn.startsWith("org.apache")
                    || fqn.startsWith("com.fasterxml") || fqn.startsWith("com.baomidou")
                    || fqn.startsWith("lombok") || fqn.startsWith("io.swagger")
                    || fqn.startsWith("org.redisson") || fqn.startsWith("com.github")) continue;
                importRefs.add(fqn);
            }
        }

        importRefs.removeAll(existingSources.keySet());

        if (importRefs.isEmpty()) return List.of();

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

    // ── 内部类型 ──────────────────────────────────────────────────────

    /** 方法行号范围。 */
    private record MethodLineRange(String filePath, int lineStart, int lineEnd) {}

    /** JGit 源码组装结果。 */
    private record SourceAssembly(String sourceCode, boolean fromGit) {}
}
