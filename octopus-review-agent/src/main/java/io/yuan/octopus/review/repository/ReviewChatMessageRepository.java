package io.yuan.octopus.review.repository;

import io.yuan.octopus.review.entity.ReviewChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewChatMessageRepository extends JpaRepository<ReviewChatMessage, Long> {

    /** 整线程历史（按时间正序）。 */
    List<ReviewChatMessage> findByThreadIdOrderByCreatedAtAscIdAsc(String threadId);

    /** 最近 N 轮（倒序取，调用方反转）。 */
    List<ReviewChatMessage> findTop20ByThreadIdOrderByCreatedAtDescIdDesc(String threadId);
}
