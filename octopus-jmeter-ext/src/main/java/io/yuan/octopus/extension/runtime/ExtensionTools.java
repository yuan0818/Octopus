package io.yuan.octopus.extension.runtime;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of published extension versions. In JMeter it is loaded once from {@code octopus-extensions.json}
 * beside the runtime JAR; the platform registers the same entries directly so validation and execution
 * share one definition.
 */
public final class ExtensionTools {
    public static final String MANIFEST = "octopus-extensions.json";
    private static final Gson GSON = new Gson();
    private static final Map<String, ExtensionEntry> ENTRIES = new ConcurrentHashMap<>();
    private static volatile boolean loaded;

    private ExtensionTools() {}

    /** Uploaded extension ({@code x.<name>}) or whitelisted JMeter built-in function ({@code jm.<name>}). */
    public static boolean isExtension(String toolId) {
        return isUploaded(toolId) || BuiltinFunctions.isBuiltin(toolId);
    }

    /** Uploaded extensions are installed separately and pinned by JAR fingerprint. */
    public static boolean isUploaded(String toolId) {
        return toolId != null && toolId.startsWith("x.");
    }

    public static Optional<ExtensionEntry> find(String id, String version) {
        if (BuiltinFunctions.isBuiltin(id)) return BuiltinFunctions.find(id, version);
        loadOnce();
        return Optional.ofNullable(ENTRIES.get(id + "@" + version));
    }

    public static List<ExtensionEntry> all() {
        loadOnce();
        var list = new ArrayList<>(ENTRIES.values());
        list.sort(Comparator.comparing(ExtensionEntry::key));
        return list;
    }

    /** Replaces the registry contents (platform side, after loading published versions). */
    public static synchronized void replaceAll(Collection<ExtensionEntry> entries) {
        ENTRIES.clear();
        for (ExtensionEntry e : entries) ENTRIES.put(e.key(), e);
        loaded = true;
    }

    public static List<ExtensionEntry> read(Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest)) return List.of();
        String text = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        List<ExtensionEntry> list = GSON.fromJson(text, new TypeToken<List<ExtensionEntry>>() {}.getType());
        return list == null ? List.of() : list;
    }

    public static void write(Path manifest, Collection<ExtensionEntry> entries) throws IOException {
        var sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(ExtensionEntry::key));
        Path temp = manifest.resolveSibling(manifest.getFileName() + ".tmp");
        Files.write(temp, GSON.toJson(sorted).getBytes(StandardCharsets.UTF_8));
        Files.move(temp, manifest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void loadOnce() {
        if (loaded) return;
        synchronized (ExtensionTools.class) {
            if (loaded) return;
            try {
                var location = ExtensionTools.class.getProtectionDomain().getCodeSource();
                if (location != null) {
                    Path jar = Paths.get(location.getLocation().toURI());
                    Path dir = Files.isDirectory(jar) ? jar : jar.getParent();
                    for (ExtensionEntry e : read(dir.resolve(MANIFEST))) ENTRIES.put(e.key(), e);
                }
            } catch (Exception ignored) {
                // No manifest: only built-in tools are available.
            }
            loaded = true;
        }
    }
}
