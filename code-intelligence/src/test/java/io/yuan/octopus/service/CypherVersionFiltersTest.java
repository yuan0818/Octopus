package io.yuan.octopus.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CypherVersionFiltersTest {

    @Test
    void branchAndVersionFragments() {
        assertEquals("true", CypherVersionFilters.branch("n", null));
        assertEquals("true", CypherVersionFilters.branch("n", " "));
        assertEquals("n.branch = $branch", CypherVersionFilters.branch("n", "main"));
        assertEquals("coalesce(n.current, true) = true", CypherVersionFilters.version("n", ""));
        assertEquals("n.commitId = $commitId", CypherVersionFilters.version("n", "abc"));
        assertEquals("(c.branch = $branch AND c.commitId = $commitId)", CypherVersionFilters.node("c", "main", "abc"));
    }

    @Test
    void parameterizedUsesAliasEverywhere() {
        String f = CypherVersionFilters.parameterized("caller");
        assertTrue(f.contains("caller.projectId = $projectId"));
        assertTrue(f.contains("caller.branch = $branch"));
        assertTrue(f.contains("coalesce(caller.current, true)"));
        assertTrue(f.contains("caller.commitId = $commitId"));
        assertFalse(f.contains("%s"));
    }
}
