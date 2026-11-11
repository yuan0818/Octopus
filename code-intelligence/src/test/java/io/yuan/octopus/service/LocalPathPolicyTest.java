package io.yuan.octopus.service;

import io.yuan.octopus.exception.ValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalPathPolicyTest {

    @TempDir Path tmp;

    @Test
    void unrestrictedWhenNotConfigured() throws Exception {
        Path project = Files.createDirectories(tmp.resolve("any/project"));
        assertEquals(project.toRealPath(), new LocalPathPolicy("").resolve(project.toString()));
    }

    @Test
    void allowsPathsUnderConfiguredRoot() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("workspace"));
        Path project = Files.createDirectories(root.resolve("svc"));
        LocalPathPolicy policy = new LocalPathPolicy(root + ", /nonexistent-root");
        assertEquals(project.toRealPath(), policy.resolve(project.toString()));
    }

    @Test
    void rejectsPathsOutsideRootIncludingDotDot() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("workspace"));
        Path outside = Files.createDirectories(tmp.resolve("secret"));
        LocalPathPolicy policy = new LocalPathPolicy(root.toString());
        assertThrows(ValidationException.class, () -> policy.resolve(outside.toString()));
        assertThrows(ValidationException.class, () -> policy.resolve(root + "/../secret"));
    }

    @Test
    void rejectsSymlinkEscapingRoot() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("workspace"));
        Path outside = Files.createDirectories(tmp.resolve("secret"));
        Path link = root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // 文件系统不支持符号链接时跳过
        }
        LocalPathPolicy policy = new LocalPathPolicy(root.toString());
        assertThrows(ValidationException.class, () -> policy.resolve(link.toString()));
    }

    @Test
    void rejectsMissingBlankOrFilePaths() throws Exception {
        LocalPathPolicy policy = new LocalPathPolicy("");
        assertThrows(ValidationException.class, () -> policy.resolve(" "));
        assertThrows(ValidationException.class, () -> policy.resolve(tmp.resolve("nope").toString()));
        Path file = Files.createFile(tmp.resolve("a.txt"));
        assertThrows(ValidationException.class, () -> policy.resolve(file.toString()));
    }
}
