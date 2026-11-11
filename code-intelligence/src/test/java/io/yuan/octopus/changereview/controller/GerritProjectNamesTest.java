package io.yuan.octopus.changereview.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GerritProjectNamesTest {

    @Test
    void extractsProjectFromCommonUrlForms() {
        assertEquals("test-repo", GerritProjectNames.projectOf("https://gerrit.example.com/a/test-repo"));
        assertEquals("group/core", GerritProjectNames.projectOf("https://gerrit.example.com/group/core.git"));
        assertEquals("group/core", GerritProjectNames.projectOf("ssh://bot@gerrit.example.com:29418/group/core"));
        assertEquals("group/core", GerritProjectNames.projectOf("bot@gerrit.example.com:group/core.git"));
        assertEquals("core", GerritProjectNames.projectOf("https://gerrit.example.com/core/"));
    }

    @Test
    void matchesOnlyExactProject() {
        assertTrue(GerritProjectNames.matches("https://g/a/core", "core"));
        assertFalse(GerritProjectNames.matches("https://g/a/platform-core", "core"));
        assertFalse(GerritProjectNames.matches("https://g/a/core-utils", "core"));
        assertFalse(GerritProjectNames.matches("https://g/a/group/core", "core"));
        assertTrue(GerritProjectNames.matches("https://g/a/group/core", "group/core"));
    }

    @Test
    void handlesNullAndBlank() {
        assertNull(GerritProjectNames.projectOf(null));
        assertNull(GerritProjectNames.projectOf(" "));
        assertFalse(GerritProjectNames.matches("https://g/a/core", null));
        assertFalse(GerritProjectNames.matches(null, "core"));
    }
}
