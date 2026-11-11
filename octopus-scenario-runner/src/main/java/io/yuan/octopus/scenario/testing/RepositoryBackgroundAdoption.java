package io.yuan.octopus.scenario.testing;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="business_repository_background_adoption")
public class RepositoryBackgroundAdoption {
    @Id String id;
    String taskId;
    Instant adoptedAt;
}
