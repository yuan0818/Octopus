package io.yuan.octopus.scenario.extension;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtensionBundleRepository extends JpaRepository<ExtensionBundle, String> {
    Optional<ExtensionBundle> findBySha256(String sha256);

    List<ExtensionBundle> findByStatusIn(Collection<ExtensionBundle.Status> statuses);

    List<ExtensionBundle> findAllByOrderByUploadedAtDesc();
}
