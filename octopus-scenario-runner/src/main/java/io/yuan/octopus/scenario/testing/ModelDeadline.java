package io.yuan.octopus.scenario.testing;

import java.time.Clock;
import java.time.Duration;

/**
 * 一次请求内多轮模型调用（工具发现、格式修正、语义修正）的总时限。
 * <p>同步接口的调用方（浏览器 270 秒、Nginx 300 秒）会先于后端放弃等待；因此每一轮的超时取
 * “单轮上限”与“剩余时间”的较小值，剩余时间不足时直接以 MODEL_TIMEOUT 结束，而不是在调用方离开后才完成。
 */
public final class ModelDeadline {
    /** 留给响应传输和后续校验的余量；剩余时间低于该值时不再发起新一轮。 */
    static final Duration MARGIN = Duration.ofSeconds(10);
    static final Duration MINIMUM_CALL = Duration.ofSeconds(15);

    private final long deadlineMillis;
    private final long budgetSeconds;
    private final Clock clock;

    private ModelDeadline(long deadlineMillis, long budgetSeconds, Clock clock) {
        this.deadlineMillis = deadlineMillis;
        this.budgetSeconds = budgetSeconds;
        this.clock = clock;
    }

    public static ModelDeadline after(Duration budget) { return after(budget, Clock.systemUTC()); }

    public static ModelDeadline after(Duration budget, Clock clock) {
        return new ModelDeadline(clock.millis() + budget.toMillis(), budget.toSeconds(), clock);
    }

    public static ModelDeadline at(long deadlineMillis, Clock clock) {
        return new ModelDeadline(deadlineMillis, Math.max(0, (deadlineMillis - clock.millis()) / 1000), clock);
    }

    /** 下一轮模型调用可用的超时；剩余时间不足时抛出 MODEL_TIMEOUT。 */
    public Duration next(Duration perCall) {
        long remaining = deadlineMillis - clock.millis() - MARGIN.toMillis();
        if (remaining < MINIMUM_CALL.toMillis())
            throw new FlowFailure(FlowFailure.Code.MODEL_TIMEOUT, null, "多轮模型调用已用完本次请求的 " + budgetSeconds + " 秒时限");
        return Duration.ofMillis(Math.min(perCall.toMillis(), remaining));
    }

    public long remainingMillis() { return deadlineMillis - clock.millis(); }
}
