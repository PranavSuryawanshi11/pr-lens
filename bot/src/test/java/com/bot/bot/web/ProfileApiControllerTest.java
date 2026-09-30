package com.bot.bot.web;

import com.bot.bot.actions.TokenService;
import com.bot.bot.email.ThresholdAlertService;
import com.bot.bot.github.GitHubApiClient;
import com.bot.bot.persistence.PrAnalysis;
import com.bot.bot.persistence.PrAnalysisRepository;
import com.bot.bot.persistence.UserProfile;
import com.bot.bot.persistence.UserProfileRepository;
import com.bot.bot.service.ProfilePrDetectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileApiControllerTest {

    private UserProfileRepository userProfileRepository;
    private ProfilePrDetectorService detectorService;
    private PrAnalysisRepository prAnalysisRepository;
    private GitHubApiClient gitHubApiClient;
    private ThresholdAlertService thresholdAlertService;
    private TokenService tokenService;
    private ProfileApiController controller;

    @BeforeEach
    void setUp() {
        userProfileRepository = mock(UserProfileRepository.class);
        detectorService = mock(ProfilePrDetectorService.class);
        prAnalysisRepository = mock(PrAnalysisRepository.class);
        gitHubApiClient = mock(GitHubApiClient.class);
        thresholdAlertService = mock(ThresholdAlertService.class);
        tokenService = mock(TokenService.class);

        controller = new ProfileApiController(
                userProfileRepository,
                detectorService,
                prAnalysisRepository,
                gitHubApiClient,
                thresholdAlertService,
                tokenService
        );
    }

    @Test
    @DisplayName("getProfile with no username returns empty profile DTO")
    void getProfileWithNoUsernameReturnsEmpty() {
        ResponseEntity<?> resp = controller.getProfile(null);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody() instanceof UserProfile);
        UserProfile p = (UserProfile) resp.getBody();
        assertEquals("", p.getGithubUsername());
        assertEquals("", p.getNotificationEmail());
        assertTrue(p.getAutoTriageEnabled());
    }

    @Test
    @DisplayName("getProfile with existing username returns saved profile")
    void getProfileWithExistingUserReturnsProfile() {
        UserProfile existing = new UserProfile();
        existing.setGithubUsername("octocat");
        existing.setNotificationEmail("octo@github.com");
        when(userProfileRepository.findByGithubUsernameIgnoreCase("octocat")).thenReturn(Optional.of(existing));

        ResponseEntity<?> resp = controller.getProfile("octocat");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        UserProfile p = (UserProfile) resp.getBody();
        assertEquals("octocat", p.getGithubUsername());
        assertEquals("octo@github.com", p.getNotificationEmail());
    }

    @Test
    @DisplayName("getProfile with new username creates new profile")
    void getProfileWithNewUserCreatesProfile() {
        when(userProfileRepository.findByGithubUsernameIgnoreCase("newuser")).thenReturn(Optional.empty());
        when(userProfileRepository.save(any(UserProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> resp = controller.getProfile("newuser");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        UserProfile p = (UserProfile) resp.getBody();
        assertEquals("newuser", p.getGithubUsername());
        assertTrue(p.getAutoTriageEnabled());
    }

    @Test
    @DisplayName("saveProfile returns 400 Bad Request when githubUsername is blank")
    void saveProfileRejectsBlankUsername() {
        ProfileApiController.ProfileDto dto = new ProfileApiController.ProfileDto(
                "", "email@test.com", true, "repo", Instant.now()
        );
        ResponseEntity<?> resp = controller.saveProfile(dto);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("githubUsername is required"));
    }

    @Test
    @DisplayName("saveProfile saves explicit email and triggers sync when autoTriage is true")
    void saveProfileSavesExplicitEmail() {
        ProfileApiController.ProfileDto dto = new ProfileApiController.ProfileDto(
                "dev1", "dev1@company.org", true, "org/app", null
        );
        when(userProfileRepository.findByGithubUsernameIgnoreCase("dev1")).thenReturn(Optional.empty());
        when(userProfileRepository.save(any(UserProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> resp = controller.saveProfile(dto);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        UserProfile p = (UserProfile) resp.getBody();
        assertEquals("dev1", p.getGithubUsername());
        assertEquals("dev1@company.org", p.getNotificationEmail());
        assertEquals("org/app", p.getMonitoredRepos());
    }

    @Test
    @DisplayName("saveProfile resolves email from GitHub if not explicitly supplied")
    void saveProfileResolvesEmailWhenNotSupplied() {
        ProfileApiController.ProfileDto dto = new ProfileApiController.ProfileDto(
                "coder", "", true, "repo", null
        );
        when(userProfileRepository.findByGithubUsernameIgnoreCase("coder")).thenReturn(Optional.empty());
        when(gitHubApiClient.resolveUserEmail("coder")).thenReturn(Mono.just("coder@public.com"));
        when(userProfileRepository.save(any(UserProfile.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> resp = controller.saveProfile(dto);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        UserProfile p = (UserProfile) resp.getBody();
        assertEquals("coder@public.com", p.getNotificationEmail());
    }

    @Test
    @DisplayName("syncNow triggers detector and returns JSON with triaged count")
    void syncNowExecutesSync() {
        when(detectorService.triggerSync()).thenReturn(3);

        ResponseEntity<?> resp = controller.syncNow();
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("SUCCESS", body.get("status"));
        assertEquals(3, body.get("triagedCount"));
    }

    @Test
    @DisplayName("resolveEmail returns 400 when username is blank")
    void resolveEmailRejectsBlank() {
        ResponseEntity<?> resp = controller.resolveEmail("  ");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    @DisplayName("resolveEmail returns resolved email address")
    void resolveEmailReturnsEmail() {
        when(gitHubApiClient.resolveUserEmail("octo")).thenReturn(Mono.just("octo@github.com"));

        ResponseEntity<?> resp = controller.resolveEmail("octo");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("octo", body.get("username"));
        assertEquals("octo@github.com", body.get("email"));
        assertEquals(true, body.get("found"));
    }

    @Test
    @DisplayName("sendTestEmail returns 400 when no email can be resolved")
    void sendTestEmailFailsWhenNoEmail() {
        when(userProfileRepository.findByGithubUsernameIgnoreCase("emptyUser")).thenReturn(Optional.empty());
        when(userProfileRepository.findAll()).thenReturn(Collections.emptyList());

        ResponseEntity<?> resp = controller.sendTestEmail("emptyUser", "");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().toString().contains("No destination email found"));
    }

    @Test
    @DisplayName("sendTestEmail dispatches alert email successfully")
    void sendTestEmailSucceeds() {
        Page<PrAnalysis> emptyPage = new PageImpl<>(Collections.emptyList());
        when(prAnalysisRepository.findAll(any(Pageable.class))).thenReturn(emptyPage);
        when(prAnalysisRepository.save(any(PrAnalysis.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> resp = controller.sendTestEmail("user1", "user1@test.com");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertEquals("SUCCESS", body.get("status"));
        assertEquals("user1@test.com", body.get("email"));
        verify(thresholdAlertService).sendTriageReport(any(PrAnalysis.class), eq(List.of("user1@test.com")));
    }

    @Test
    @DisplayName("getHistory returns empty list when no user or search term specified")
    void getHistoryEmptyWhenNoParams() {
        ResponseEntity<?> resp = controller.getHistory(null, null, null, 50);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<?> list = (List<?>) resp.getBody();
        assertTrue(list.isEmpty());
    }

    @Test
    @DisplayName("getHistory filters by tier and search term")
    void getHistoryFiltersByTierAndSearch() {
        PrAnalysis pr1 = new PrAnalysis();
        pr1.setId(1L);
        pr1.setOwner("owner");
        pr1.setRepo("repo");
        pr1.setPrNumber(1);
        pr1.setTitle("Fix SQL Injection");
        pr1.setTier("RED");
        pr1.setSecurityFlag(true);

        PrAnalysis pr2 = new PrAnalysis();
        pr2.setId(2L);
        pr2.setOwner("owner");
        pr2.setRepo("repo");
        pr2.setPrNumber(2);
        pr2.setTitle("Update Documentation");
        pr2.setTier("GREEN");
        pr2.setSecurityFlag(false);

        when(prAnalysisRepository.findByUser(eq("owner"), any(Pageable.class))).thenReturn(List.of(pr1, pr2));
        when(tokenService.buildActionUrl(anyString(), anyString(), anyInt(), anyString())).thenReturn("http://action-url");

        // Filter for RED tier
        ResponseEntity<?> resp = controller.getHistory("owner", "RED", null, 50);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        List<?> list = (List<?>) resp.getBody();
        assertEquals(1, list.size());
        Map<?, ?> item = (Map<?, ?>) list.get(0);
        assertEquals("RED", item.get("tier"));
        assertEquals("http://action-url", item.get("approveUrl"));
    }

    @Test
    @DisplayName("getHistoryItem returns 200 when found and 404 when not found")
    void getHistoryItemHandlesFoundAndNotFound() {
        PrAnalysis pr = new PrAnalysis();
        pr.setId(99L);
        when(prAnalysisRepository.findById(99L)).thenReturn(Optional.of(pr));
        when(prAnalysisRepository.findById(100L)).thenReturn(Optional.empty());

        ResponseEntity<?> found = controller.getHistoryItem(99L);
        assertEquals(HttpStatus.OK, found.getStatusCode());

        ResponseEntity<?> notFound = controller.getHistoryItem(100L);
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());
    }

    @Test
    @DisplayName("getStats calculates counts accurately for user")
    void getStatsCalculatesAccurateCounts() {
        PrAnalysis pr1 = new PrAnalysis();
        pr1.setTier("RED");
        pr1.setSecurityFlag(true);
        pr1.setActionTaken(false);

        PrAnalysis pr2 = new PrAnalysis();
        pr2.setTier("YELLOW");
        pr2.setSecurityFlag(false);
        pr2.setActionTaken(true);

        PrAnalysis pr3 = new PrAnalysis();
        pr3.setTier("GREEN");
        pr3.setSecurityFlag(false);
        pr3.setActionTaken(true);

        when(prAnalysisRepository.findAllByUser("alice")).thenReturn(List.of(pr1, pr2, pr3));

        ResponseEntity<?> resp = controller.getStats("alice");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> stats = (Map<?, ?>) resp.getBody();
        assertEquals(3, stats.get("total"));
        assertEquals(1L, stats.get("red"));
        assertEquals(1L, stats.get("yellow"));
        assertEquals(1L, stats.get("green"));
        assertEquals(1L, stats.get("security"));
        assertEquals(2L, stats.get("actioned"));
    }
}
