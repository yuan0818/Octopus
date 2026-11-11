package io.yuan.octopus.service;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

class GitImpactCoordinatesTest {
    @TempDir Path directory;

    @Test
    void retainsDeletedGuardCoordinatesAndDeletedFilePath() throws Exception {
        try (Git git = Git.init().setDirectory(directory.toFile()).call()) {
            Path source = directory.resolve("Order.java");
            Files.writeString(
                    source,
                    "class Order {\n void refund() {\n  checkPaid();\n  refundPayment();\n }\n}\n");
            git.add().addFilepattern(".").call();
            String base =
                    git.commit()
                            .setMessage("base")
                            .setAuthor("Test", "test@example.invalid")
                            .call()
                            .name();
            Files.writeString(
                    source, "class Order {\n void refund() {\n  refundPayment();\n }\n}\n");
            git.add().addFilepattern(".").call();
            String target =
                    git.commit()
                            .setMessage("remove guard")
                            .setAuthor("Test", "test@example.invalid")
                            .call()
                            .name();
            GitService service = new GitService(null, directory.toString(), false);
            var change = service.computeChangedLineRanges(directory, base, target).getFirst();
            assertTrue(change.changedLineRanges().isEmpty());
            assertArrayEquals(new int[] {3, 3}, change.oldLineRanges().getFirst());
            git.rm().addFilepattern("Order.java").call();
            String deleted =
                    git.commit()
                            .setMessage("delete")
                            .setAuthor("Test", "test@example.invalid")
                            .call()
                            .name();
            var removal = service.computeChangedLineRanges(directory, target, deleted).getFirst();
            assertEquals("DELETE", removal.changeType());
            assertEquals(source.toString(), removal.oldFilePath());
            assertEquals(source.toString(), removal.filePath());
        }
    }
}
