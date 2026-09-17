package com.sprint.sprint.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TriageResultTest {

    @Test
    void recordExposesTierSecurityAndSuggestedAction() {
        TriageResult result = new TriageResult(
                TriageResult.Tier.RED,
                true,
                TriageResult.SuggestedAction.CONSIDER_CLOSING
        );

        assertEquals(TriageResult.Tier.RED, result.tier());
        assertTrue(result.securityFlag());
        assertEquals(TriageResult.SuggestedAction.CONSIDER_CLOSING, result.suggestedAction());
    }

    @Test
    void tierEnumHasAllThreeLevels() {
        assertEquals(3, TriageResult.Tier.values().length);
        assertArrayEquals(
                new TriageResult.Tier[]{TriageResult.Tier.GREEN, TriageResult.Tier.YELLOW, TriageResult.Tier.RED},
                TriageResult.Tier.values()
        );
    }
}