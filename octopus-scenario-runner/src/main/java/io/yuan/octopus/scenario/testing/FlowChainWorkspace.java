package io.yuan.octopus.scenario.testing;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="business_flow_chain")
public class FlowChainWorkspace {
    @Id @Column(length=36) public String id;
    @Version public long version;
    @Column(name="request_id",nullable=false,unique=true,length=64) public String requestId;
    @Column(name="request_hash",nullable=false,length=64) public String requestHash;
    @Column(name="flow_id",nullable=false,length=120) public String flowId;
    @Column(name="active_flow",unique=true,length=120) public String activeFlow;
    @Column(nullable=false,length=24) public String state;
    @Column(name="snapshot_encrypted",nullable=false,columnDefinition="LONGTEXT") public String snapshotEncrypted;
    @Column(name="created_at",nullable=false) public Instant createdAt;
    @Column(name="updated_at",nullable=false) public Instant updatedAt;
}
