package io.yuan.octopus.scenario.dsl;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DslSourceRepository extends JpaRepository<DslSourceRecord, String> {
}
