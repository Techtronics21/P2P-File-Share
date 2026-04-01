package org.arnavthakur;

import junit.framework.TestCase;

import org.arnavthakur.utils.FixedWindowRateLimiter;
import org.arnavthakur.utils.HeaderUtils;

public class AppTest extends TestCase {
    public void testRateLimiterEnforcesPerWindowLimit() {
        FixedWindowRateLimiter rateLimiter = new FixedWindowRateLimiter(2, 60_000);

        assertTrue(rateLimiter.allow("127.0.0.1", 1_000));
        assertTrue(rateLimiter.allow("127.0.0.1", 1_001));
        assertFalse(rateLimiter.allow("127.0.0.1", 1_002));
        assertTrue(rateLimiter.allow("127.0.0.1", 62_000));
    }

    public void testFilenameSanitizationStripsHeaderBreakingCharacters() {
        String sanitized = HeaderUtils.sanitizeFilename("..\\evil\r\nname\".txt", "fallback.txt");

        assertEquals("evilname_.txt", sanitized);
        assertEquals("attachment; filename=\"evilname_.txt\"",
                HeaderUtils.buildAttachmentDisposition("..\\evil\r\nname\".txt"));
    }

    public void testFilenameSanitizationFallsBackForBlankValues() {
        assertEquals("fallback.txt", HeaderUtils.sanitizeFilename("", "fallback.txt"));
    }
}
