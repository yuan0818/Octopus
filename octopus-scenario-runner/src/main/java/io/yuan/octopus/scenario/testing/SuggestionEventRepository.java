package io.yuan.octopus.scenario.testing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SuggestionEventRepository extends JpaRepository<SuggestionEvent, String> {
    List<SuggestionEvent> findTop50000ByCreatedAtAfterOrderByCreatedAtDesc(Instant after);
}
