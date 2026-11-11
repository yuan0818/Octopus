package io.yuan.octopus.scenario.testing;

/**
 * Pure rules for FR-POLL-01/03/04, independent of persistence. A step may poll only if its API has an active
 * authorization, the step's bounds are equal to or stricter than the authorization, and — when the flow pinned
 * an authorization — it is still the same authorization revision.
 */
public final class PollAuthorizationRules {
    public static final long MIN_INTERVAL = 200, MAX_INTERVAL = 30_000, MIN_TIMEOUT = 1_000, MAX_TIMEOUT = 300_000;
    public static final int MAX_ATTEMPTS = 120;

    private PollAuthorizationRules() {}

    /** The bounds a user authorizes. */
    public static final class Grant {
        public final String id;
        public final int revision;
        public final long intervalMillis;
        public final int maxAttempts;
        public final long timeoutMillis;

        public Grant(String id, int revision, long intervalMillis, int maxAttempts, long timeoutMillis) {
            this.id = id;
            this.revision = revision;
            this.intervalMillis = intervalMillis;
            this.maxAttempts = maxAttempts;
            this.timeoutMillis = timeoutMillis;
        }

        public String ref() { return id + "@" + revision; }
    }

    /** Validates bounds a user is about to authorize; returns an error message or null. */
    public static String invalidBounds(long intervalMillis, int maxAttempts, long timeoutMillis) {
        if (intervalMillis < MIN_INTERVAL || intervalMillis > MAX_INTERVAL) return "轮询间隔需要 " + MIN_INTERVAL + "–" + MAX_INTERVAL + " 毫秒";
        if (timeoutMillis < MIN_TIMEOUT || timeoutMillis > MAX_TIMEOUT) return "轮询总超时需要 " + MIN_TIMEOUT + "–" + MAX_TIMEOUT + " 毫秒";
        if (maxAttempts < 2 || maxAttempts > MAX_ATTEMPTS) return "最大尝试次数需要 2–" + MAX_ATTEMPTS;
        if (intervalMillis > timeoutMillis) return "轮询间隔不能大于总超时";
        return null;
    }

    /** Requests a poll would send at most (first request included); mirrors {@code PollChecks.attempts}. */
    public static long attempts(long intervalMillis, long timeoutMillis) {
        return timeoutMillis / intervalMillis + 1;
    }

    /** Returns null if the step's poll is covered by the grant, otherwise a fixed, user-facing reason. */
    public static String problem(long intervalMillis, long timeoutMillis, String pinnedRef, Grant grant) {
        if (grant == null) return "该 API 未被授权轮询（请在 API 上显式授权，或改用固定等待 + 单次查询）";
        if (pinnedRef != null && !pinnedRef.equals(grant.ref())) return "轮询授权已变更或撤销，请重新确认流程";
        if (intervalMillis < grant.intervalMillis) return "轮询间隔 " + intervalMillis + " ms 小于授权值 " + grant.intervalMillis + " ms";
        if (timeoutMillis > grant.timeoutMillis) return "轮询总超时 " + timeoutMillis + " ms 大于授权值 " + grant.timeoutMillis + " ms";
        if (attempts(intervalMillis, timeoutMillis) > grant.maxAttempts)
            return "轮询最多会发送 " + attempts(intervalMillis, timeoutMillis) + " 次，超过授权的 " + grant.maxAttempts + " 次";
        return null;
    }

    /** The strictest poll a step gets from {@code poll: true}: the authorized interval and a timeout within max attempts. */
    public static long[] defaults(Grant grant) {
        long timeout = Math.min(grant.timeoutMillis, grant.intervalMillis * (grant.maxAttempts - 1L));
        return new long[] {grant.intervalMillis, Math.max(timeout, grant.intervalMillis)};
    }
}
