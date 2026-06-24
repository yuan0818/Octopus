package io.yuan.octopus.infra.memory.model;

/**
 * 项目知识的状态。
 *
 * <ul>
 *   <li>TENTATIVE — 初步知识，置信度不足</li>
 *   <li>CONFIRMED — 被多次验证确认</li>
 *   <li>ESTABLISHED — 已确立，置信度高</li>
 *   <li>CONTROVERSIAL — 存在争议</li>
 *   <li>STALE — 过时知识</li>
 * </ul>
 */
public enum KnowledgeStatus {
    TENTATIVE,
    CONFIRMED,
    ESTABLISHED,
    CONTROVERSIAL,
    STALE
}
