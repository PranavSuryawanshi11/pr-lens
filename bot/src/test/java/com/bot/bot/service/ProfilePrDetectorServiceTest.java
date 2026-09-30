package com.bot.bot.service;

import com.bot.bot.email.ThresholdAlertService;
import com.bot.bot.github.GitHubApiClient;
import com.bot.bot.persistence.PrAnalysis;
import com.bot.bot.persistence.PrAnalysisRepository;
import com.bot.bot.persistence.UserProfile;
import com.bot.bot.persistence.UserProfileRepository;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfilePrDetectorServiceTest {

    private UserProfileRepository userProfileRepository;
    private GitHubApiClient gitHubApiClient;
    private ReviewOrchestrator reviewOrchestrator;
    private PrAnalysisRepository prAnalysisRepository;
    private ThresholdAlertService thresholdAlertService;
    private ProfilePrDetectorService detectorService;

    @BeforeEach
    void setUp() {
        userProfileRepository = mock(UserProfileRepository.class);
        gitHubApiClient = mock(GitHubApiClient.class);
        reviewOrchestrator = mock(ReviewOrchestrator.class);
        prAnalysisRepository = mock(PrAnalysisRepository.class);
        thresholdAlertService = mock(ThresholdAlertService.class);

        detectorService = new ProfilePrDetectorService(
                userProfileRepository,
                gitHubApiClient,
                reviewOrchestrator,
                prAnalysisRepository,
                thresholdAlertService
        );
    }

    @Test
    @DisplayName("triggerSync returns 0 when no profiles have auto-triage enabled")
    void returnsZeroWhenNoActiveProfiles() {
        when(userProfileRepository.findByAutoTriageEnabledTrue()).thenReturn(Collections.emptyList());

        int count = detectorService.triggerSync();
        assertEquals(0, count);
        verifyNoInteractions(gitHubApiClient);
    }

    @Test
    @DisplayName("triggerSync skips profile when githubUsername is blank")
    void skipsProfileWithBlankUsername() {
        UserProfile profile = new UserProfile();
        profile.setGithubUsername("");
        when(userProfileRepository.findByAutoTriageEnabledTrue()).thenReturn(List.of(profile));

        int count = detectorService.triggerSync();
        assertEquals(0, count);
        verify(userProfileRepository).save(profile);
    }

    @Test
    @DisplayName("Auto-discovers repos when monitoredRepos is empty")
    void autoDiscoversReposWhenMonitoredReposBlank() {
        UserProfile profile = new UserProfile();
        profile.setGithubUsername("alice");
        profile.setMonitoredRepos("");
        when(userProfileRepository.findByAutoTriageEnabledTrue()).thenReturn(List.of(profile));

        when(gitHubApiClient.fetchUserRepositories("alice")).thenReturn(Mono.just(List.of("alice/repo1")));
        when(gitHubApiClient.fetchOpenPullRequests("alice", "repo1")).thenReturn(Mono.just(Collections.emptyList()));

        int count = detectorService.triggerSync();
        assertEquals(0, count);
        verify(gitHubApiClient).fetchUserRepositories("alice");
        verify(gitHubApiClient).fetchOpenPullRequests("alice", "repo1");
    }

    @Test
    @DisplayName("Parses comma, semicolon, newline delimited monitoredRepos and strips full URLs")
    void parsesDelimitedReposAndStripsUrls() {
        UserProfile profile = new UserProfile();
        profile.setGithubUsername("bob");
        profile.setMonitoredRepos("https://github.com/bob/repoA, orgB/repoB;\nhttps://github.com/bob/repoC/");
        when(userProfileRepository.findByAutoTriageEnabledTrue()).thenReturn(List.of(profile));

        when(gitHubApiClient.fetchOpenPullRequests(anyString(), anyString())).thenReturn(Mono.just(Collections.emptyList()));

        detectorService.triggerSync();

        verify(gitHubApiClient).fetchOpenPullRequests("bob", "repoA");
        verify(gitHubApiClient).fetchOpenPullRequests("orgB", "repoB");
        verify(gitHubApiClient).fetchOpenPullRequests("bob", "repoC");
    }

    @Test
    @DisplayName("Skips PR if head SHA has already been analyzed")
    void skipsAlreadyAnalyzedPrSha() {
        UserProfile profile = new UserProfile();
        profile.setGithubUsername("charlie");
        profile.setMonitoredRepos("charlie/app");
        when(userProfileRepository.findByAutoTriageEnabledTrue()).thenReturn(List.of(profile));

        JsonObject pr = new JsonObject();
        pr.addProperty("number", 101);
        JsonObject head = new JsonObject();
        head.addProperty("sha", "commit123");
        pr.add("head", head);

        when(gitHubApiClient.fetchOpenPullRequests("charlie", "app")).thenReturn(Mono.just(List.of(pr)));
        when(prAnalysisRepository.existsByOwnerRepoPrSha("charlie", "app", 101, "commit123")).thenReturn(true);

        int count = detectorService.triggerSync();
        assertEquals(0, count);
        verify(reviewOrchestrator, never()).triagePullRequest(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Triages new PR, sets targetUser, saves, and dispatches threshold alert")
    void triagesNewPrAndAlerts() {
        UserProfile profile = new UserProfile();
        profile.setGithubUsername("dev");
        profile.setNotificationEmail("dev@example.com");
        profile.setMonitoredRepos("dev/service");
        when(userProfileRepository.findByAutoTriageEnabledTrue()).thenReturn(List.of(profile));

        JsonObject pr = new JsonObject();
        pr.addProperty("number", 42);
        JsonObject head = new JsonObject();
        head.addProperty("sha", "newsha999");
        pr.add("head", head);

        when(gitHubApiClient.fetchOpenPullRequests("dev", "service")).thenReturn(Mono.just(List.of(pr)));
        when(prAnalysisRepository.existsByOwnerRepoPrSha("dev", "service", 42, "newsha999")).thenReturn(false);

        PrAnalysis analysis = new PrAnalysis();
        analysis.setOwner("dev");
        analysis.setRepo("service");
        analysis.setPrNumber(42);
        analysis.setTier("YELLOW");

        when(reviewOrchestrator.triagePullRequest("dev", "service", 42)).thenReturn(Mono.just(analysis));

        int count = detectorService.triggerSync();
        assertEquals(1, count);
        assertEquals("dev", analysis.getTargetUser());
        verify(prAnalysisRepository).save(analysis);
        verify(thresholdAlertService).sendTriageReport(analysis, List.of("dev@example.com"));
    }

    @Test
    @DisplayName("Resolves notification email via GitHubApiClient when profile email is blank")
    void resolvesEmailFromGitHubWhenBlank() {
        UserProfile profile = new UserProfile();
        profile.setGithubUsername("dev2");
        profile.setNotificationEmail("");
        profile.setMonitoredRepos("dev2/repo");
        when(userProfileRepository.findByAutoTriageEnabledTrue()).thenReturn(List.of(profile));

        JsonObject pr = new JsonObject();
        pr.addProperty("number", 1);
        JsonObject head = new JsonObject();
        head.addProperty("sha", "sha1");
        pr.add("head", head);

        when(gitHubApiClient.fetchOpenPullRequests("dev2", "repo")).thenReturn(Mono.just(List.of(pr)));
        when(prAnalysisRepository.existsByOwnerRepoPrSha(anyString(), anyString(), anyInt(), anyString())).thenReturn(false);

        PrAnalysis analysis = new PrAnalysis();
        when(reviewOrchestrator.triagePullRequest("dev2", "repo", 1)).thenReturn(Mono.just(analysis));
        when(gitHubApiClient.resolveUserEmail("dev2", "dev2", "repo", 1)).thenReturn(Mono.just("resolved@github.com"));

        detectorService.triggerSync();

        assertEquals("resolved@github.com", profile.getNotificationEmail());
        verify(thresholdAlertService).sendTriageReport(analysis, List.of("resolved@github.com"));
    }

    @Test
    @DisplayName("Error checking one repository does not abort checking other repositories")
    void handlesExceptionInOneRepoGracefully() {
        UserProfile profile = new UserProfile();
        profile.setGithubUsername("resilient");
        profile.setMonitoredRepos("resilient/bad-repo, resilient/good-repo");
        when(userProfileRepository.findByAutoTriageEnabledTrue()).thenReturn(List.of(profile));

        when(gitHubApiClient.fetchOpenPullRequests("resilient", "bad-repo"))
                .thenReturn(Mono.error(new RuntimeException("GitHub 404")));
        when(gitHubApiClient.fetchOpenPullRequests("resilient", "good-repo"))
                .thenReturn(Mono.just(Collections.emptyList()));

        assertDoesNotThrow(() -> detectorService.triggerSync());
        verify(gitHubApiClient).fetchOpenPullRequests("resilient", "good-repo");
    }

    @Test
    @DisplayName("runScheduledDetection catches any thrown exception without crashing")
    void scheduledDetectionCatchesExceptions() {
        when(userProfileRepository.findByAutoTriageEnabledTrue())
                .thenThrow(new RuntimeException("Database offline"));

        assertDoesNotThrow(() -> detectorService.runScheduledDetection());
    }
}
