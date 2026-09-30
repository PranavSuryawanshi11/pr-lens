
package com.bot.bot.email;

import com.bot.bot.actions.TokenService;
import com.bot.bot.persistence.PrAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailTemplateExtendedTest {

    private TokenService tokenService;
    private EmailTemplate emailTemplate;

    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);
        when(tokenService.buildActionUrl(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn("");
        emailTemplate = new EmailTemplate(tokenService);
    }

    @Test
    @DisplayName("renderDigest with empty list shows 'No pull requests to review'")
    void renderDigestEmptyList() {
        String html = emailTemplate.renderDigest(Collections.emptyList());
        assertNotNull(html);
        assertTrue(html.contains("No pull requests to review"));
        assertTrue(html.contains("0 PRs analyzed"));
    }

    @Test
    @DisplayName("renderDigest with multiple analyses renders each PR and action links")
    void renderDigestWithAnalyses() {
        PrAnalysis a1 = new PrAnalysis();
        a1.setOwner("org");
        a1.setRepo("repo");
        a1.setPrNumber(10);
        a1.setTier("GREEN");
        a1.setTitle("Add README");

        when(tokenService.buildActionUrl(anyString(), anyString(), anyInt(), eq("approve")))
                .thenReturn("http://approve");
        when(tokenService.buildActionUrl(anyString(), anyString(), anyInt(), eq("reject")))
                .thenReturn("http://reject");

        String html = emailTemplate.renderDigest(List.of(a1));
        assertTrue(html.contains("1 PR analyzed"));
        assertTrue(html.contains("org/repo#10"));
        assertTrue(html.contains("Approve"));
        assertTrue(html.contains("Reject"));
    }

    @Test
    @DisplayName("renderAlert renders RED tier with High Risk badge and security warning")
    void renderAlertRedTierWithSecurity() {
        PrAnalysis a = new PrAnalysis();
        a.setOwner("company");
        a.setRepo("auth-service");
        a.setPrNumber(77);
        a.setTitle("Update JWT logic");
        a.setAuthor("intern");
        a.setAuthorReputation("FIRST_TIME_CONTRIBUTOR");
        a.setTier("RED");
        a.setSecurityFlag(true);
        a.setFindingsJson("[{\"filePath\":\"Jwt.java\",\"severity\":\"CRITICAL\",\"message\":\"Hardcoded secret\"}]");

        when(tokenService.buildActionUrl(anyString(), anyString(), anyInt(), eq("approve")))
                .thenReturn("http://approve-action");
        when(tokenService.buildActionUrl(anyString(), anyString(), anyInt(), eq("reject")))
                .thenReturn("http://reject-action");

        String html = emailTemplate.renderAlert(a);

        assertTrue(html.contains("company/auth-service#77"));
        assertTrue(html.contains("🔴 High Risk"));
        assertTrue(html.contains("SECURITY"));
        assertTrue(html.contains("Security Warning:"));
        assertTrue(html.contains("First-time Contributor"));
        assertTrue(html.contains("Hardcoded secret"));
        assertTrue(html.contains("http://approve-action"));
        assertTrue(html.contains("http://reject-action"));
    }

    @Test
    @DisplayName("renderAlert handles TRUSTED_MAINTAINER badge and GREEN tier")
    void renderAlertTrustedMaintainerGreen() {
        PrAnalysis a = new PrAnalysis();
        a.setOwner("facebook");
        a.setRepo("react");
        a.setPrNumber(100);
        a.setAuthor("gaearon");
        a.setAuthorReputation("TRUSTED_MAINTAINER");
        a.setTier("GREEN");
        a.setSecurityFlag(false);
        a.setFindingsJson("[]");

        String html = emailTemplate.renderAlert(a);
        assertTrue(html.contains("⭐ Trusted Maintainer"));
        assertTrue(html.contains("🟢 Low Risk"));
        assertTrue(html.contains("Automated Checks Passed"));
    }

    @Test
    @DisplayName("cleanFeatureText strips line statistics and snippet markers")
    void testsCleanFeatureText() {
        String raw = "Added 12 line(s) of new implementation across 1 file(s). (prior code included: `oldVal`) updated logic";
        String cleaned = EmailTemplate.cleanFeatureText(raw);

        assertFalse(cleaned.contains("line(s)"));
        assertFalse(cleaned.contains("prior code included:"));
        assertTrue(cleaned.contains("updated logic"));
    }

    @Test
    @DisplayName("isDifferenceApplicable returns false for clean addition or empty before state")
    void testsIsDifferenceApplicable() {
        assertFalse(EmailTemplate.isDifferenceApplicable("Clean addition", "Created new file Foo.java"));
        assertFalse(EmailTemplate.isDifferenceApplicable("No previous code modifications", "New feature"));
        assertTrue(EmailTemplate.isDifferenceApplicable("old logic", "Updated logic in 'AuthService'"));
    }

    @Test
    @DisplayName("formatBeforeCell and formatAfterCell provide clean diff descriptions")
    void testsCellFormatting() {
        String before = EmailTemplate.formatBeforeCell("", "updated class 'User'");
        assertEquals("Previous class implementation before these modifications.", before);

        String after = EmailTemplate.formatAfterCell("Added new endpoint /api/users");
        assertTrue(after.contains("Added new endpoint /api/users"));
    }

    @Test
    @DisplayName("formatPointWiseChanges generates HTML with icons")
    void testsPointWiseChanges() {
        PrAnalysis a = new PrAnalysis();
        a.setTitle("feat: Analytics Service");

        String html = EmailTemplate.formatPointWiseChanges("Created new file Analytics.java", a);
        assertTrue(html.contains("New Component:"));
        assertTrue(html.contains("Integration Scope:"));
    }

    @Test
    @DisplayName("renderAlert renders executive summary, functional breakdown, gap analysis, and maintainer decision")
    void renderAlertWithHolisticFunctionalAnalysis() {
        PrAnalysis a = new PrAnalysis();
        a.setOwner("org");
        a.setRepo("payment-service");
        a.setPrNumber(42);
        a.setTitle("Add Stripe Webhook Handler");
        a.setTier("YELLOW");
        a.setSummary("Title: Add Stripe Webhook Handler\n"
                + "Executive Summary: Introduces an asynchronous Stripe webhook endpoint to process incoming payment intent events and update customer subscription state.\n"
                + "Functional Changes:\n"
                + "- Added StripeWebhookController with POST /api/v1/stripe/webhook endpoint\n"
                + "- Added signature validation using Stripe-Signature header\n"
                + "What to Add or Edit:\n"
                + "- Add replay attack prevention with webhook event timestamp validation\n"
                + "- Add unit tests covering invalid webhook signatures\n"
                + "Decision Rationale: Architecture is sound but missing signature replay protection tests. Request changes before merge.\n"
                + "Recommendation: REQUEST_CHANGES\n"
                + "Risk: MEDIUM\n"
                + "AI-likelihood: LOW");

        String html = emailTemplate.renderAlert(a);

        assertTrue(html.contains("Overview & Executive Summary"));
        assertTrue(html.contains("Introduces an asynchronous Stripe webhook endpoint"));
        assertTrue(html.contains("Detailed Functional Breakdown"));
        assertTrue(html.contains("Added StripeWebhookController with POST /api/v1/stripe/webhook"));
        assertTrue(html.contains("What Contributor Needs to Add / Edit"));
        assertTrue(html.contains("Add replay attack prevention with webhook event timestamp validation"));
        assertTrue(html.contains("Add unit tests covering invalid webhook signatures"));
        assertTrue(html.contains("Maintainer Decision & Verdict"));
        assertTrue(html.contains("REQUEST SPECIFIC CHANGES"));
        assertTrue(html.contains("Architecture is sound but missing signature replay protection tests"));
    }
}
