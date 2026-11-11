package io.yuan.octopus.scenario.extension;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.yuan.octopus.extension.runtime.ExtensionEntry;
import io.yuan.octopus.extension.runtime.ExtensionTools;
import io.yuan.octopus.scenario.extension.ExtensionModel.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Upload → static inspection → conflict checks → isolated verification → publish (FR-EXT-20–28, D-04: no
 * human approval). Publishing installs the JAR into the JMeter {@code lib/ext} directory and rewrites the
 * runtime manifest; the same entries are registered in-process so validation and execution share them.
 */
@Service
public class ExtensionService {
    private static final Logger log = LoggerFactory.getLogger(ExtensionService.class);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final EnumSet<ExtensionBundle.Status> INSTALLED = EnumSet.of(ExtensionBundle.Status.PUBLISHED, ExtensionBundle.Status.DISABLED);

    public record Summary(String id, String fileName, String sha256, long sizeBytes, String status, String deprecated,
                          List<Tool> tools, List<Issue> issues, List<String> unannotatedFunctions, String uploadedBy,
                          Instant uploadedAt, String publishedBy, Instant publishedAt) {
        public boolean publishable() {
            return "VALIDATED".equals(status);
        }
    }

    private final ExtensionBundleRepository bundles;
    private final Path storage;
    private final Path jmeterHome;

    public ExtensionService(ExtensionBundleRepository bundles,
                            @Value("${scenario.extensions.storage-dir:data/jmeter-extensions}") String storage,
                            @Value("${scenario.jmeter.home:}") String jmeterHome) {
        this.bundles = bundles;
        this.storage = Path.of(storage).toAbsolutePath();
        this.jmeterHome = jmeterHome == null || jmeterHome.isBlank() ? null : Path.of(jmeterHome).toAbsolutePath();
    }

    /** Registers installed extensions and re-installs any missing JARs (idempotent). */
    @EventListener(ApplicationReadyEvent.class)
    public void initialise() {
        try {
            syncRuntime();
        } catch (RuntimeException e) {
            log.warn("Extension runtime sync failed: {}", e.getMessage());
        }
    }

    @Transactional
    public Summary upload(byte[] jar, String fileName, String user, boolean publish) {
        require(jar != null && jar.length > 0, "请选择 JAR 文件");
        require(fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(".jar") && fileName.length() <= 255, "只支持 .jar 文件");
        require(jar.length <= JarInspector.MAX_JAR_BYTES, "JAR 超过 20 MB");
        String sha = JarInspector.sha256(jar);
        var existing = bundles.findBySha256(sha);
        if (existing.isPresent()) return summary(existing.get());
        Inspection inspection = JarInspector.inspect(jar);
        var issues = new ArrayList<>(inspection.issues);
        issues.addAll(conflicts(inspection, null));
        if (issues.stream().noneMatch(Issue::error) && !inspection.tools.isEmpty()) issues.addAll(verify(jar, inspection.tools));
        var bundle = new ExtensionBundle();
        bundle.id = UUID.randomUUID().toString();
        bundle.fileName = Path.of(fileName).getFileName().toString();
        bundle.sha256 = sha;
        bundle.sizeBytes = jar.length;
        bundle.toolsJson = GSON.toJson(inspection.tools);
        bundle.issuesJson = GSON.toJson(issues);
        bundle.classNamesJson = GSON.toJson(inspection.classNames);
        bundle.uploadedBy = user;
        bundle.uploadedAt = bundle.updatedAt = Instant.now();
        boolean blocked = issues.stream().anyMatch(Issue::error) || inspection.tools.isEmpty();
        bundle.status = blocked ? ExtensionBundle.Status.REJECTED : ExtensionBundle.Status.VALIDATED;
        try {
            Files.createDirectories(storage);
            Files.write(storage.resolve(sha + ".jar"), jar);
        } catch (IOException e) {
            throw new IllegalStateException("扩展存储目录不可写");
        }
        bundles.save(bundle);
        var summary = summary(bundle, inspection.unannotatedFunctions);
        if (publish && !blocked) return publish(bundle.id, user);
        return summary;
    }

    @Transactional
    public Summary publish(String id, String user) {
        var bundle = get(id);
        require(bundle.status == ExtensionBundle.Status.VALIDATED, "只有通过全部校验的扩展可以发布");
        var conflicts = conflicts(null, bundle);
        require(conflicts.stream().noneMatch(Issue::error), conflicts.stream().map(i -> i.message).findFirst().orElse("存在冲突"));
        require(jmeterHome != null && Files.isDirectory(jmeterHome.resolve("lib/ext")), "未配置 JMETER_HOME，无法安装扩展");
        bundle.status = ExtensionBundle.Status.PUBLISHED;
        bundle.publishedBy = user;
        bundle.publishedAt = bundle.updatedAt = Instant.now();
        bundles.save(bundle);
        syncRuntime();
        return summary(bundle);
    }

    @Transactional
    public Summary disable(String id) {
        var bundle = get(id);
        require(bundle.status == ExtensionBundle.Status.PUBLISHED, "只有已发布的扩展可以停用");
        bundle.status = ExtensionBundle.Status.DISABLED;
        bundle.updatedAt = Instant.now();
        bundles.save(bundle);
        syncRuntime();
        return summary(bundle);
    }

    @Transactional
    public Summary enable(String id) {
        var bundle = get(id);
        require(bundle.status == ExtensionBundle.Status.DISABLED, "只有已停用的扩展可以重新启用");
        bundle.status = ExtensionBundle.Status.PUBLISHED;
        bundle.updatedAt = Instant.now();
        bundles.save(bundle);
        syncRuntime();
        return summary(bundle);
    }

    @Transactional
    public Summary deprecate(String id, String note) {
        var bundle = get(id);
        require(INSTALLED.contains(bundle.status), "只有已发布的扩展可以标记弃用");
        require(note != null && !note.isBlank() && note.length() <= 500, "请说明弃用原因或替代版本（≤500 字符）");
        bundle.deprecated = note.strip();
        bundle.updatedAt = Instant.now();
        bundles.save(bundle);
        return summary(bundle);
    }

    public List<Summary> list() {
        return bundles.findAllByOrderByUploadedAtDesc().stream().map(this::summary).toList();
    }

    public Summary summary(String id) {
        return summary(get(id));
    }

    /** Tools usable for new derivations: published, not disabled. Deprecated tools stay listed with a notice. */
    public List<Published> active() {
        var result = new ArrayList<Published>();
        for (var bundle : bundles.findByStatusIn(EnumSet.of(ExtensionBundle.Status.PUBLISHED)))
            for (Tool tool : tools(bundle)) result.add(new Published(tool, bundle.sha256, bundle.deprecated, warnings(bundle, tool)));
        return result;
    }

    public record Published(Tool tool, String sha256, String deprecated, List<Issue> warnings) {}

    /**
     * FR-FN-03 / FR-EXT-28: before a run, every extension version the IR uses must be installed in JMeter with
     * the fingerprint of the published JAR. Throws with a fixed message otherwise (the run is INCONCLUSIVE).
     */
    public void verifyRuntime(Path home, Set<String> keys) {
        if (keys.isEmpty()) return;
        require(home != null, "运行使用了 JMeter 扩展，但未配置 JMETER_HOME");
        List<ExtensionEntry> installed;
        try {
            installed = ExtensionTools.read(home.resolve("lib/ext").resolve(ExtensionTools.MANIFEST));
        } catch (IOException e) {
            throw new IllegalArgumentException("JMeter 扩展清单不可读取");
        }
        for (String key : keys) {
            var entry = installed.stream().filter(e -> e.key().equals(key)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("扩展 " + key + " 未安装到 JMeter"));
            Path jar = home.resolve("lib/ext").resolve(entry.jar);
            try {
                require(Files.isRegularFile(jar) && JarInspector.sha256(Files.readAllBytes(jar)).equals(entry.sha256),
                        "扩展 " + key + " 的 JAR 指纹与发布版本不一致");
            } catch (IOException e) {
                throw new IllegalArgumentException("扩展 " + key + " 的 JAR 不可读取");
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private List<Issue> verify(byte[] jar, List<Tool> tools) {
        if (jmeterHome == null || !ExtensionVerifier.probeInstalled(jmeterHome))
            return List.of(new Issue("ERROR", "VERIFY_UNAVAILABLE",
                    "无法执行隔离测试：未配置 JMETER_HOME，或 JMeter 中的 octopus-jmeter-ext 版本过旧（请重新安装 distribution）", null));
        try {
            var verifier = new ExtensionVerifier(Path.of(System.getProperty("java.home"), "bin", "java"), jmeterHome,
                    ExtensionVerifier.classpathFor(jmeterHome));
            return verifier.verify(jar, tools);
        } catch (IOException e) {
            return List.of(new Issue("ERROR", "VERIFY_UNAVAILABLE", "无法读取 JMeter 安装目录", null));
        }
    }

    /** FR-EXT-26: immutable versions, unique reference keys and no class-name collisions with installed bundles. */
    private List<Issue> conflicts(Inspection inspection, ExtensionBundle self) {
        List<Tool> tools = inspection != null ? inspection.tools : tools(self);
        Set<String> classes = new HashSet<>(inspection != null ? inspection.classNames : classNames(self));
        String sha = inspection != null ? inspection.sha256 : self.sha256;
        var issues = new ArrayList<Issue>();
        for (var other : bundles.findAll()) {
            if (other.sha256.equals(sha) || other.status == ExtensionBundle.Status.REJECTED) continue;
            for (Tool mine : tools)
                for (Tool theirs : tools(other))
                    if (Objects.equals(mine.name, theirs.name) && Objects.equals(mine.version, theirs.version))
                        issues.add(new Issue("ERROR", "VERSION_IMMUTABLE", mine.name + "@" + mine.version
                                + " 已由另一个 JAR 注册；已注册的版本不可修改，请提升版本号", mine.className));
            if (!INSTALLED.contains(other.status)) continue;
            for (String c : classNames(other))
                if (classes.contains(c))
                    issues.add(new Issue("ERROR", "CLASS_CONFLICT", "类 " + c + " 与已发布的扩展 " + other.fileName
                            + " 冲突；请把依赖重定位到带版本段的包中", c));
        }
        return issues;
    }

    /** Copies installed JARs into JMeter lib/ext, rewrites the manifest, and refreshes the in-process registry. */
    synchronized void syncRuntime() {
        var entries = new ArrayList<ExtensionEntry>();
        var installed = bundles.findByStatusIn(INSTALLED);
        for (var bundle : installed) for (Tool tool : tools(bundle)) entries.add(entry(bundle, tool));
        ExtensionTools.replaceAll(entries);
        if (jmeterHome == null) return;
        Path ext = jmeterHome.resolve("lib/ext");
        if (!Files.isDirectory(ext)) return;
        try {
            for (var bundle : installed) {
                Path target = ext.resolve(jarName(bundle));
                if (Files.isRegularFile(target) && JarInspector.sha256(Files.readAllBytes(target)).equals(bundle.sha256)) continue;
                Path source = storage.resolve(bundle.sha256 + ".jar");
                require(Files.isRegularFile(source), "扩展存储中缺少 " + bundle.fileName);
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            ExtensionTools.write(ext.resolve(ExtensionTools.MANIFEST), entries);
        } catch (IOException e) {
            throw new IllegalStateException("无法把扩展安装到 JMeter lib/ext");
        }
    }

    static String jarName(ExtensionBundle bundle) {
        return "octopus-x-" + bundle.sha256.substring(0, 16) + ".jar";
    }

    static ExtensionEntry entry(ExtensionBundle bundle, Tool tool) {
        var e = new ExtensionEntry();
        e.id = tool.catalogId();
        e.name = tool.name;
        e.version = tool.version;
        e.className = tool.className;
        e.referenceKey = tool.referenceKey;
        e.sha256 = bundle.sha256;
        e.jar = jarName(bundle);
        e.determinism = tool.determinism;
        e.stages = new ArrayList<>(tool.stages);
        e.sensitiveOutput = tool.sensitiveOutput;
        e.outputType = tool.output.type;
        for (Param p : tool.params) e.params.add(new ExtensionEntry.Param(p.name, p.type, p.required, p.sensitive));
        return e;
    }

    private ExtensionBundle get(String id) {
        return bundles.findById(id).orElseThrow(() -> new IllegalArgumentException("扩展不存在"));
    }

    static List<Tool> tools(ExtensionBundle bundle) {
        List<Tool> list = GSON.fromJson(bundle.toolsJson, new TypeToken<List<Tool>>() {}.getType());
        return list == null ? List.of() : list;
    }

    private static List<String> classNames(ExtensionBundle bundle) {
        List<String> list = GSON.fromJson(bundle.classNamesJson, new TypeToken<List<String>>() {}.getType());
        return list == null ? List.of() : list;
    }

    private static List<Issue> issues(ExtensionBundle bundle) {
        List<Issue> list = GSON.fromJson(bundle.issuesJson, new TypeToken<List<Issue>>() {}.getType());
        return list == null ? List.of() : list;
    }

    private static List<Issue> warnings(ExtensionBundle bundle, Tool tool) {
        return issues(bundle).stream().filter(i -> !i.error() && (i.className == null || i.className.equals(tool.className))).toList();
    }

    private Summary summary(ExtensionBundle b) {
        return summary(b, List.of());
    }

    private Summary summary(ExtensionBundle b, List<String> unannotated) {
        return new Summary(b.id, b.fileName, b.sha256, b.sizeBytes, b.status.name(), b.deprecated, tools(b), issues(b),
                unannotated, b.uploadedBy, b.uploadedAt, b.publishedBy, b.publishedAt);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
