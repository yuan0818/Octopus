package io.yuan.octopus.scenario.testing;

import static io.yuan.octopus.scenario.testing.IrValidator.require;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

/**
 * Phase 2.1：HTTP 步骤轮询的校验规则与常量。
 * <p>
 * 语义：重复发送同一请求（含预处理），直到该步骤全部检查项（状态码、JSON 断言、Schema、状态观察）通过，
 * 或累计耗时超过 {@code timeoutMillis}。中间轮次只记为 POLL_RETRY，不提交上下文、不触发失败停止；
 * 最后一轮按普通语义判定，因此超时即 FAIL（与固定等待失败一致），不会被静默放过。
 * 数据库 / Redis / Kafka 验证只在轮询结束后执行一次。
 */
public final class PollChecks {

    public static final String CAPABILITY = "http.poll";
    public static final long MIN_INTERVAL = 200;
    public static final long MAX_INTERVAL = 30_000;
    public static final long MIN_TIMEOUT = 1_000;
    public static final long MAX_TIMEOUT = 300_000;
    public static final int MAX_ATTEMPTS = 120;

    private PollChecks() {
    }

    public static void validate(Step step, java.util.Set<String> capabilities) {
        Poll poll = step.poll();
        if (poll == null) return;
        require(capabilities.contains(CAPABILITY), "IR omits polling capability");
        require(poll.intervalMillis() >= MIN_INTERVAL && poll.intervalMillis() <= MAX_INTERVAL,
                "轮询间隔需要 " + MIN_INTERVAL + "–" + MAX_INTERVAL + " 毫秒: " + step.id());
        require(poll.timeoutMillis() >= MIN_TIMEOUT && poll.timeoutMillis() <= MAX_TIMEOUT,
                "轮询总时长需要 " + MIN_TIMEOUT + "–" + MAX_TIMEOUT + " 毫秒: " + step.id());
        require(poll.intervalMillis() <= poll.timeoutMillis(), "轮询间隔不能大于总时长: " + step.id());
        require(attempts(poll) <= MAX_ATTEMPTS, "轮询次数不能超过 " + MAX_ATTEMPTS + "（请增大间隔）: " + step.id());
    }

    /** 最多请求次数（含首次）。 */
    public static int attempts(Poll poll) {
        return (int) Math.min(Integer.MAX_VALUE, poll.timeoutMillis() / poll.intervalMillis() + 1);
    }
}
