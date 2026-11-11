package io.yuan.octopus.scenario.testing;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="business_repository_analysis_history")
public class RepositoryAnalysisHistory {
    @Id @Column(length=64) public String id;
    @Column(name="scope_id",nullable=false,length=64) public String scopeId;
    @Column(name="is_current",nullable=false) public boolean current;
    @Column(name="summary_encrypted",nullable=false,columnDefinition="LONGTEXT") public String summaryEncrypted;
    @Column(name="snapshot_encrypted",nullable=false,columnDefinition="LONGTEXT") public String snapshotEncrypted;
    @Column(name="updated_at",nullable=false) public Instant updatedAt;
}
