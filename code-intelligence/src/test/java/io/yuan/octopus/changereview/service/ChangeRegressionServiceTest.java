package io.yuan.octopus.changereview.service;

import io.yuan.octopus.changereview.entity.ChangeReviewTask;
import io.yuan.octopus.changereview.repository.ChangeReviewTaskRepository;
import io.yuan.octopus.entity.RepoConfig;
import io.yuan.octopus.repository.AnalyzedProjectRepository;
import io.yuan.octopus.repository.RepoConfigRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChangeRegressionServiceTest {

    private final RepoConfigRepository repoConfigs = mock(RepoConfigRepository.class);
    private final GerritRestClient gerrit = mock(GerritRestClient.class);

    private ChangeRegressionService service(String token) {
        return new ChangeRegressionService(mock(ChangeReviewTaskRepository.class), repoConfigs, mock(AnalyzedProjectRepository.class),
                gerrit, new RegressionCommentBuilder(), "http://scenario", "http://ci", token, "test", "http://octopus");
    }

    @Test
    void externalRefRoundTrips() {
        ChangeReviewTask task = ChangeReviewTask.builder().id(9L).repoConfigId(3L).changeNumber("1234").patchsetNumber(2).build();
        String ref = ChangeRegressionService.externalRef(task);
        assertEquals("gerrit:3:1234:2:9", ref);
        var parsed = ChangeRegressionService.parseRef(ref);
        assertEquals(3L, parsed.repoConfigId());
        assertEquals("1234", parsed.changeNumber());
        assertNull(ChangeRegressionService.parseRef("ci#7"));
        assertNull(ChangeRegressionService.parseRef("gerrit:x:1"));
    }

    @Test
    void callbackRequiresConfiguredMatchingToken() {
        assertThrows(IllegalStateException.class, () -> service("").onCallback("t", Map.of()));
        assertThrows(SecurityException.class, () -> service("secret").onCallback("wrong", Map.of()));
        assertThrows(SecurityException.class, () -> service("secret").onCallback(null, Map.of()));
    }

    @Test
    void callbackPostsCommentToReferencedChange() {
        RepoConfig repo = RepoConfig.builder().id(3L).repoUrl("https://g/a/x").branch("main").build();
        when(repoConfigs.findById(3L)).thenReturn(Optional.of(repo));
        when(gerrit.postComment(eq(repo), eq("1234"), anyString())).thenReturn(true);

        boolean ok = service("secret").onCallback("secret", Map.of("id", "b1", "verdict", "PASS",
                "externalRef", "gerrit:3:1234:2:9", "counts", Map.of("total", 1, "pass", 1)));

        assertTrue(ok);
        verify(gerrit).postComment(eq(repo), eq("1234"), contains("✅ 通过"));
    }

    @Test
    void callbackWithoutGerritRefIsIgnored() {
        assertTrue(service("secret").onCallback("secret", Map.of("id", "b1", "externalRef", "ci#7")));
        verifyNoInteractions(gerrit);
    }
}
