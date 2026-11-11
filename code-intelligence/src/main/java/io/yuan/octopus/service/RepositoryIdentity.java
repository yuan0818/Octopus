package io.yuan.octopus.service;

import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import java.nio.file.Path;

/** Local identity only: never guesses that two sibling directories are one repository. */
public final class RepositoryIdentity {
    private RepositoryIdentity() {}

    public static Path canonicalPath(String path) {
        Path location = Path.of(path).toAbsolutePath().normalize();
        try { return location.toRealPath(); }
        catch (Exception ignored) { return location; }
    }

    public static String root(String path) {
        if (path == null || path.isBlank()) return null;
        Path location = Path.of(path).toAbsolutePath().normalize();
        try {
            var builder = new FileRepositoryBuilder().findGitDir(location.toFile());
            if (builder.getGitDir() != null) {
                try (var repository = builder.build()) {
                    if (!repository.isBare()) return repository.getWorkTree().toPath().toRealPath().toString();
                }
            }
            return location.toRealPath().toString();
        } catch (Exception ignored) {
            return location.toString();
        }
    }
}
