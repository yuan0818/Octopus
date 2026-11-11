package io.yuan.octopus.scenario.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * JMeter 插件管理：上传 JAR → 存储到 pluginDir → 执行前自动部署到 lib/ext/。
 */
@RestController
@RequestMapping("/api/jmeter-plugins")
public class PluginController {

    @Value("${scenario.jmeter.plugin-dir:data/jmeter-plugins}")
    private String pluginDir;

    @GetMapping
    public List<Map<String, Object>> list() {
        String pluginDir2 = getPluginDir();
        File dir = new File(pluginDir2);
        List<Map<String, Object>> out = new ArrayList<>();
        if (dir.exists()) {
            File[] jars = dir.listFiles((_d, name) -> name.endsWith(".jar"));
            if (jars != null) {
                for (File jar : Arrays.stream(jars).sorted(Comparator.comparing(File::getName)).toArray(File[]::new)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", jar.getName());
                    row.put("size", jar.length());
                    row.put("lastModified", new Date(jar.lastModified()).toString());
                    out.add(row);
                }
            }
        }
        return out;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.endsWith(".jar")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only .jar files are supported"));
        }
        // 安全：不允许路径穿越
        if (filename.contains("/") || filename.contains("..")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid filename"));
        }
        try {
            Path target = Path.of(getPluginDir(), filename);
            Files.createDirectories(target.getParent());
            file.transferTo(target);
            return ResponseEntity.ok(Map.of("name", filename, "size", target.toFile().length()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@PathVariable String name) {
        if (name.contains("/") || name.contains("..")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid filename"));
        }
        File jar = new File(getPluginDir(), name);
        if (jar.exists() && jar.delete()) {
            return ResponseEntity.ok(Map.of("deleted", name));
        }
        return ResponseEntity.notFound().build();
    }

    private String getPluginDir() {
        return pluginDir;
    }
}
