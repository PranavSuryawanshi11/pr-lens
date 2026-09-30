package com.bot.bot.email;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bot.bot.actions.TokenService;
import com.bot.bot.config.AppProperties;
import com.bot.bot.persistence.Meta;
import com.bot.bot.persistence.MetaRepository;
import com.bot.bot.persistence.PrAnalysis;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

class EmailTemplateTest {

    private TokenService tokenService(boolean configured) {
        AppProperties props = new AppProperties();
        if (configured) {
            props.setActionSecret("secret");
            props.setBaseUrl("https://bot.example.com");
        }
        MetaRepository repo = Mockito.mock(MetaRepository.class);
        Mockito.when(repo.findById(Mockito.anyString())).thenReturn(Optional.empty());
        Mockito.when(repo.save(Mockito.any(Meta.class))).thenAnswer(inv -> inv.getArgument(0));
        return new TokenService(props, repo);
    }

    private PrAnalysis analysis(String owner, String repo, int n, String tier, boolean security) {
        PrAnalysis a = new PrAnalysis();
        a.setOwner(owner);
        a.setRepo(repo);
        a.setPrNumber(n);
        a.setTier(tier);
        a.setSecurityFlag(security);
        a.setSummary("summary line\nmore");
        return a;
    }

    @Test
    void digestIncludesRowAndActionLinksWhenConfigured() {
        EmailTemplate tpl = new EmailTemplate(tokenService(true));
        String body = tpl.renderDigest(List.of(analysis("acme", "api", 7, "RED", false)));

        assertTrue(body.contains("acme/api#7"));
        assertTrue(body.contains("RED"));
        assertTrue(body.contains("Approve"));
        assertTrue(body.contains("https://bot.example.com/action?token="));
    }

    @Test
    void digestOmitsActionLinksWhenTokenUnconfigured() {
        EmailTemplate tpl = new EmailTemplate(tokenService(false));
        String body = tpl.renderDigest(List.of(analysis("acme", "api", 7, "RED", false)));

        assertTrue(body.contains("acme/api#7"));
        assertFalse(body.contains("Approve"));
    }

    @Test
    void alertMarksSecurityAndIncludesLinks() {
        EmailTemplate tpl = new EmailTemplate(tokenService(true));
        String body = tpl.renderAlert(analysis("acme", "api", 7, "YELLOW", true));

        assertTrue(body.contains("SECURITY"));
        assertTrue(body.contains("Approve"));
        assertTrue(body.contains("Reject"));
    }

    @Test
    void testDifferenceTableRendersWhenApplicableAndOmitsCodeAndLineNumbers() {
        EmailTemplate tpl = new EmailTemplate(tokenService(true));
        PrAnalysis a = analysis("acme", "api", 10, "YELLOW", false);
        a.setTitle("Refactor user authentication service");
        a.setChangeSummaryBefore("Previously had 15 line(s) across 1 file(s) that were modified or removed (prior code included: `int oldAuth = 1;`).");
        a.setChangeSummaryAfter("Updated class 'AuthService' in 'AuthService.java' - Added 30 line(s) of new implementation across 1 file(s) (introduced: `public boolean checkToken()`).");

        String body = tpl.renderAlert(a);

        // Verify Difference Table rendered
        assertTrue(body.contains("Feature Differences (Before vs After PR)"));
        assertTrue(body.contains("Before Pull Request"));
        assertTrue(body.contains("After Pull Request"));
        assertTrue(body.contains("What Changed in PR"));

        // Verify raw code and line counts are stripped
        assertFalse(body.contains("prior code included:"));
        assertFalse(body.contains("`int oldAuth = 1;`"));
        assertFalse(body.contains("line(s)"));
        assertFalse(body.contains("`public boolean checkToken()`"));
    }

    @Test
    void testPointWiseChangesRendersWhenDifferenceNotApplicable() {
        EmailTemplate tpl = new EmailTemplate(tokenService(true));
        PrAnalysis a = analysis("acme", "api", 11, "GREEN", false);
        a.setTitle("Add new Computer Networks module");
        a.setChangeSummaryBefore("Clean addition: new functionality introduced without replacing existing code.");
        a.setChangeSummaryAfter("Added one more subject 'Computer Networks' in subjects/ and in Computer Networks added '7 layer OSI model' - Added 50 line(s) of new implementation across 1 file(s) (introduced: `### Physical Layer`).");

        String body = tpl.renderAlert(a);

        // Verify Point-Wise list rendered, not difference table
        assertTrue(body.contains("Key Changes (Point-Wise Summary)"));
        assertTrue(body.contains("Feature Added:"));
        assertTrue(body.contains("Computer Networks"));
        assertTrue(body.contains("7 layer OSI model"));
        assertFalse(body.contains("Feature Differences (Before vs After PR)"));

        // Verify raw code and line counts are stripped
        assertFalse(body.contains("line(s)"));
        assertFalse(body.contains("introduced:"));
        assertFalse(body.contains("`### Physical Layer`"));
    }

    @Test
    void testFindingsOmitLineNumbers() {
        EmailTemplate tpl = new EmailTemplate(tokenService(true));
        PrAnalysis a = analysis("acme", "api", 12, "RED", false);
        a.setFindingsJson("[{\"filePath\":\"src/main/Auth.java\",\"lineNumber\":88,\"severity\":\"HIGH\",\"message\":\"Unchecked null pointer possibility\",\"suggestion\":\"Add null check\"}]");

        String body = tpl.renderAlert(a);

        assertTrue(body.contains("src/main/Auth.java"));
        assertTrue(body.contains("Unchecked null pointer possibility"));
        assertTrue(body.contains("Add null check"));
        // Ensure exact line number ':88' is NOT displayed
        assertFalse(body.contains("Auth.java:88"));
    }
}
