package io.yuan.octopus.scenario.testing;
import jakarta.persistence.*;
import java.time.Instant;
@Entity @Table(name="business_repository_analysis")
public class RepositoryAnalysisWorkspace {
    @Id @Column(length=64) public String id;
    @Version public long version;
    @Column(nullable=false,length=24) public String state;
    @Column(length=36) public String owner;
    @Column(name="lease_until",nullable=false) public long leaseUntil;
    @Column(name="snapshot_encrypted",nullable=false,columnDefinition="LONGTEXT") public String snapshotEncrypted;
    @Column(name="updated_at",nullable=false) public Instant updatedAt;
}
