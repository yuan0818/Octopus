package io.yuan.octopus.scenario.testing;

import java.time.Instant;
import java.util.*;

/** Safe, actionable diagnostics. Never serialize exception bodies, URLs, prompts or credentials. */
public class FlowFailure extends IllegalStateException {
    public enum Code {
        MODEL_TIMEOUT, MODEL_RATE_LIMIT, MODEL_AUTH, MODEL_CONFIG, MODEL_UNAVAILABLE,
        MODEL_RESPONSE_INVALID, MODEL_PATCH_INVALID, CONTEXT_STALE, CONTEXT_UNAVAILABLE,
        WORKER_INTERRUPTED, QUEUE_BUSY, STORAGE_UNAVAILABLE, UNKNOWN
    }
    public record BindingIssue(String code, String input, String expectedType, String actualType,
                               String contextKey, List<String> availableContextKeys, String suggestion) {}
    public record Detail(String code, String stage, String nodeId, String message, String suggestion,
                         boolean retryable, Integer upstreamStatus, String diagnosticId, String occurredAt, BindingIssue bindingIssue) {
        public Detail(String code, String stage, String nodeId, String message, String suggestion,
                      boolean retryable, Integer upstreamStatus, String diagnosticId, String occurredAt) {
            this(code, stage, nodeId, message, suggestion, retryable, upstreamStatus, diagnosticId, occurredAt, null);
        }
    }
    public final Code code;
    public final Integer upstreamStatus;
    private final String safeReason;
    private final BindingIssue bindingIssue;
    public FlowFailure(Code code) { this(code, null, null); }
    public FlowFailure(Code code, Integer upstreamStatus, String safeReason) {
        this(code, upstreamStatus, safeReason, null);
    }
    private FlowFailure(Code code, Integer upstreamStatus, String safeReason, BindingIssue bindingIssue) {
        super(message(code) + (safeReason == null ? "" : "：" + safeReason));
        this.code=code; this.upstreamStatus=upstreamStatus; this.safeReason=safeReason; this.bindingIssue=bindingIssue;
    }
    static FlowFailure validation(RuntimeException error) {
        return new FlowFailure(Code.MODEL_PATCH_INVALID, null, safeValidationReason(error),
                error instanceof FlowBindingException binding ? binding.issue : null);
    }
    public Detail detail(String stage, String nodeId) {
        return new Detail(code.name(),stage,nodeId,getMessage(),bindingIssue == null ? suggestion(code) : bindingIssue.suggestion(),retryable(code),upstreamStatus,UUID.randomUUID().toString(),Instant.now().toString(),bindingIssue);
    }
    public static FlowFailure classify(Throwable e, String stage) {
        for(Throwable cause=e;cause!=null;cause=cause.getCause()) if(cause instanceof FlowFailure failure) return failure;
        if(e instanceof org.springframework.dao.DataAccessException) return new FlowFailure(Code.STORAGE_UNAVAILABLE);
        if("PREPARING".equals(stage)) return new FlowFailure(Code.CONTEXT_UNAVAILABLE);
        if("CONFIRMING".equals(stage)) return new FlowFailure(Code.CONTEXT_STALE);
        return new FlowFailure(Code.UNKNOWN);
    }
    static String safeValidationReason(Throwable error) {
        if (error instanceof FlowBindingException || error instanceof RepositoryAnalysisService.ReportValidationException) return error.getMessage();
        // Validator messages are fixed Chinese text. Do not return Gson paths, model-generated field names or values.
        String text=error.getMessage();
        if(text!=null) {
            for(var entry:Map.of("预处理上下文未声明:","请求预处理引用了未声明的上下文", "预处理引用的 API 输入尚未填写:","请求预处理引用的接口入参尚未填写", "预处理只能引用前面动作的输出:","请求预处理引用了未知或后置动作的输出", "预处理绑定类型不匹配:","请求预处理的数据来源与工具参数类型不一致").entrySet())
                if(text.startsWith(entry.getKey())) return entry.getValue();
            if(Set.of("Platform 签名必须使用实际请求 Query","Platform 签名必须使用最终请求体","Platform 签名和实际请求头必须使用同一次计算结果","请将签名结果绑定至 sign 请求头","请明确选择 MD5 或 SHA-256 算法").contains(text)) return text;
        }
        return text!=null && text.matches("[\\p{IsHan}，；、（） ]{2,100}") ? text : null;
    }
    private static String message(Code code) { return switch(code) {
        case MODEL_TIMEOUT -> "模型响应超时，当前节点尚未完成";
        case MODEL_RATE_LIMIT -> "模型服务限流或额度不足";
        case MODEL_AUTH -> "模型服务拒绝认证或访问权限";
        case MODEL_CONFIG -> "所选模型未配置、已停用或连接配置无效";
        case MODEL_UNAVAILABLE -> "无法连接模型服务，或模型服务暂时不可用";
        case MODEL_RESPONSE_INVALID -> "模型返回内容不是符合约定的 JSON";
        case MODEL_PATCH_INVALID -> "模型建议未通过契约与流程校验";
        case CONTEXT_STALE -> "候选记忆已过期，背景、接口版本或上游配置发生变化";
        case CONTEXT_UNAVAILABLE -> "当前版本契约、源码或项目背景读取失败";
        case WORKER_INTERRUPTED -> "执行进程中断或失去任务租约，已恢复到最近检查点";
        case QUEUE_BUSY -> "生成队列已满，任务已保存";
        case STORAGE_UNAVAILABLE -> "任务存储暂时不可用，尚未确认本次写入";
        case UNKNOWN -> "当前节点发生未分类错误，已保留最近检查点";
    }; }
    private static String suggestion(Code code) { return switch(code) {
        case MODEL_AUTH, MODEL_CONFIG -> "在 Agent 配置中检查模型是否启用、密钥和访问权限，修复后重试当前节点。";
        case MODEL_RATE_LIMIT -> "等待限流解除或检查模型额度后重试，已完成节点无需重做。";
        case MODEL_TIMEOUT, MODEL_UNAVAILABLE -> "检查模型服务连接，恢复后重试当前节点；不会重复应用已完成节点。";
        case MODEL_RESPONSE_INVALID -> "返回格式修正后仍未通过校验，请根据字段类型或输出不完整提示检查模型输出，重试当前节点；已完成节点保留。";
        case MODEL_PATCH_INVALID -> "可重试当前节点；若持续失败，检查模型和接口契约，或取消后调整业务目标再生成。";
        case CONTEXT_STALE -> "从最早发生变化的节点重新生成后续链路，再统一应用。";
        case CONTEXT_UNAVAILABLE -> "检查代码分析服务和所选提交是否仍可读取，修复后继续。";
        case WORKER_INTERRUPTED -> "点击继续补全，从未完成节点重试。中断前的模型调用可能已产生费用，但迟到结果不会覆盖检查点。";
        case QUEUE_BUSY -> "等待其他任务结束后点击继续补全。";
        case STORAGE_UNAVAILABLE -> "等待数据库恢复后重新连接任务，以服务端已保存状态为准。";
        case UNKNOWN -> "可重试当前节点；若仍失败，请提供诊断编号排查。";
    }; }
    private static boolean retryable(Code code) { return !Set.of(Code.MODEL_AUTH,Code.MODEL_CONFIG,Code.CONTEXT_STALE).contains(code); }
}
