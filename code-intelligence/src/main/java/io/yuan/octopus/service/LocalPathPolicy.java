package io.yuan.octopus.service;

import io.yuan.octopus.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 本地目录分析的路径白名单（{@code POST /api/analysis/local}）。
 * <p>
 * 未配置时保持原行为（允许任意目录，适合单机自用），启动时打印一次告警；
 * 对外部署时设置 {@code octopus.analysis.local.allowed-roots}（或环境变量
 * {@code OCTOPUS_LOCAL_ALLOWED_ROOTS}，逗号分隔），只允许分析这些目录之下的项目。
 * <p>
 * 校验基于 {@link Path#toRealPath}：先解析符号链接与 {@code ..}，再判断前缀，
 * 避免用 {@code /workspace/link -> /etc} 之类的路径绕过。
 */
@Component
public class LocalPathPolicy {

    private static final Logger log = LoggerFactory.getLogger(LocalPathPolicy.class);

    private final List<Path> allowedRoots;

    public LocalPathPolicy(@Value("${octopus.analysis.local.allowed-roots:}") String allowedRoots) {
        this.allowedRoots = parse(allowedRoots);
        if (this.allowedRoots.isEmpty()) {
            log.warn("octopus.analysis.local.allowed-roots 未配置：本地分析可读取服务器上任意目录。"
                    + "对外部署时请设置 OCTOPUS_LOCAL_ALLOWED_ROOTS（例如 /workspace）");
        } else {
            log.info("Local analysis restricted to: {}", this.allowedRoots);
        }
    }

    /**
     * 校验并返回规范化后的真实路径。
     *
     * @throws ValidationException 目录不存在、不是目录或不在白名单内（HTTP 400）
     */
    public Path resolve(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            throw new ValidationException("projectPath must not be blank");
        }
        Path real;
        try {
            real = Path.of(projectPath).toRealPath();
        } catch (IOException | RuntimeException e) {
            throw new ValidationException("Path is not a valid directory: " + projectPath);
        }
        if (!Files.isDirectory(real)) {
            throw new ValidationException("Path is not a valid directory: " + projectPath);
        }
        if (!allowedRoots.isEmpty() && allowedRoots.stream().noneMatch(real::startsWith)) {
            throw new ValidationException("Path is outside the allowed analysis roots: " + projectPath);
        }
        return real;
    }

    List<Path> allowedRoots() {
        return allowedRoots;
    }

    private static List<Path> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(LocalPathPolicy::realOrNormalized)
                .toList();
    }

    /** 白名单根目录本身也取真实路径（macOS 的 /tmp → /private/tmp 等）。 */
    private static Path realOrNormalized(String root) {
        Path p = Path.of(root);
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }
}
