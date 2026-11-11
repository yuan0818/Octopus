package io.yuan.octopus.scenario.testing;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** FR-POLL-01/03/04: only explicitly authorized APIs may be polled, within the authorized bounds. */
class PollAuthorizationRulesTest {
    private static final PollAuthorizationRules.Grant GRANT = new PollAuthorizationRules.Grant("a1", 2, 2_000, 30, 60_000);

    @Test
    void noAuthorizationMeansNoPolling() {
        assertNotNull(PollAuthorizationRules.problem(2_000, 10_000, null, null));
    }

    @Test
    void stepBoundsMustBeEqualOrStricter() {
        assertNull(PollAuthorizationRules.problem(2_000, 58_000, null, GRANT));
        assertNull(PollAuthorizationRules.problem(5_000, 30_000, "a1@2", GRANT));
        assertTrue(PollAuthorizationRules.problem(1_000, 10_000, null, GRANT).contains("间隔"));
        assertTrue(PollAuthorizationRules.problem(2_000, 90_000, null, GRANT).contains("总超时"));
        assertTrue(PollAuthorizationRules.problem(2_000, 60_000, null, GRANT).contains("31"), "60s/2s+1 = 31 attempts > 30");
    }

    @Test
    void pinnedRevisionMustMatch() {
        assertTrue(PollAuthorizationRules.problem(2_000, 10_000, "a1@1", GRANT).contains("变更"));
        assertTrue(PollAuthorizationRules.problem(2_000, 10_000, "other@2", GRANT).contains("变更"));
    }

    @Test
    void boundsValidationAndDefaults() {
        assertNull(PollAuthorizationRules.invalidBounds(2_000, 30, 60_000));
        assertNotNull(PollAuthorizationRules.invalidBounds(100, 30, 60_000));
        assertNotNull(PollAuthorizationRules.invalidBounds(2_000, 121, 60_000));
        assertNotNull(PollAuthorizationRules.invalidBounds(2_000, 1, 60_000));
        assertNotNull(PollAuthorizationRules.invalidBounds(20_000, 5, 10_000));
        long[] d = PollAuthorizationRules.defaults(GRANT);
        assertEquals(2_000, d[0]);
        assertEquals(58_000, d[1]);
        assertNull(PollAuthorizationRules.problem(d[0], d[1], GRANT.ref(), GRANT));
    }
}
