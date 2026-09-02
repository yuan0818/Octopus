package io.yuan.octopus.changereview.repository;

import io.yuan.octopus.changereview.entity.ChangeReviewTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChangeReviewTaskRepository extends JpaRepository<ChangeReviewTask, Long> {

    /** patchset 幂等：按 (repoConfigId, change, patchset, patchsetCommit) 查重（BR-CTR-049）。 */
    Optional<ChangeReviewTask> findByRepoConfigIdAndChangeNumberAndPatchsetNumber(
            Long repoConfigId, String changeNumber, Integer patchsetNumber);

    /** 同 Change 的所有任务，按 patchset 倒序（查询列表/取代判断）。 */
    List<ChangeReviewTask> findByRepoConfigIdAndChangeNumberOrderByPatchsetNumberDesc(
            Long repoConfigId, String changeNumber);

    /** 同上（分页版本）。 */
    Page<ChangeReviewTask> findByRepoConfigIdAndChangeNumberOrderByPatchsetNumberDesc(
            Long repoConfigId, String changeNumber, Pageable pageable);

    /**
     * patchset 取代：将同 Change 下处于在途态（PENDING/RUNNING）的任务标记为 SUPERSEDED（BR-CTR-047）。
     * 排除当前新任务 id（传 null 时无排除）。
     */
    @Modifying
    @Query("UPDATE ChangeReviewTask t SET t.status = 'SUPERSEDED' " +
           "WHERE t.repoConfigId = :repoConfigId AND t.changeNumber = :changeNumber " +
           "AND t.status IN ('PENDING','RUNNING') " +
           "AND (:excludeId IS NULL OR t.id <> :excludeId)")
    int markInFlightAsSuperseded(@Param("repoConfigId") Long repoConfigId,
                                @Param("changeNumber") String changeNumber,
                                @Param("excludeId") Long excludeId);

    /**
     * 消费侧乐观占位：PENDING → RUNNING 原子翻转。
     * 返回 0 表示任务已被其他消费者持有或已进入终态（MQ 重投 / 恢复补发去重依据）。
     */
    @Modifying
    @Query("UPDATE ChangeReviewTask t SET t.status = 'RUNNING' " +
           "WHERE t.id = :id AND t.status = 'PENDING'")
    int claimPending(@Param("id") Long id);

    /**
     * 崩溃恢复：RUNNING → PENDING（重新入队前调用，保证 claimPending 的互斥语义）。
     */
    @Modifying
    @Query("UPDATE ChangeReviewTask t SET t.status = 'PENDING' " +
           "WHERE t.id = :id AND t.status = 'RUNNING'")
    int resetRunningToPending(@Param("id") Long id);

    /** 孤儿恢复轮询：status=RUNNING 的任务（消费者重启后需恢复）。 */
    List<ChangeReviewTask> findByStatus(String status);

    /** 按状态分页查询（列表接口）。 */
    Page<ChangeReviewTask> findByStatus(String status, Pageable pageable);
}
