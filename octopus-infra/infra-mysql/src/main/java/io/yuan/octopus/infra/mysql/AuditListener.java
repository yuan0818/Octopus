package io.yuan.octopus.infra.mysql;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * JPA audit callback that auto-fills {@code createdAt} / {@code updatedAt}.
 * <p>
 * Usage on a business entity:
 * <pre>{@code
 * @Entity
 * @EntityListeners(AuditListener.class)
 * public class MyEntity {
 *     private LocalDateTime createdAt;
 *     private LocalDateTime updatedAt;
 *     // getters and setters (Lombok @Setter works)
 * }
 * }</pre>
 * <p>
 * The entity class must define {@code setCreatedAt(LocalDateTime)} and
 * {@code setUpdatedAt(LocalDateTime)} methods (or the fields with Lombok {@code @Setter}).
 * If either method is absent the corresponding field is silently skipped.
 */
public class AuditListener {

    @PrePersist
    public void prePersist(Object entity) {
        setIfPresent(entity, "setCreatedAt", LocalDateTime.now());
        setIfPresent(entity, "setUpdatedAt", LocalDateTime.now());
    }

    @PreUpdate
    public void preUpdate(Object entity) {
        setIfPresent(entity, "setUpdatedAt", LocalDateTime.now());
    }

    private void setIfPresent(Object entity, String methodName, Object value) {
        try {
            Method m = entity.getClass().getMethod(methodName, LocalDateTime.class);
            m.invoke(entity, value);
        } catch (NoSuchMethodException ignored) {
            // entity doesn't have this method — skip silently
        } catch (Exception e) {
            throw new RuntimeException("AuditListener failed to invoke " + methodName + " on "
                    + entity.getClass().getSimpleName(), e);
        }
    }
}
