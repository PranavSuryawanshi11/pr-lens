package com.bot.bot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

    Optional<UserProfile> findByGithubUsernameIgnoreCase(String githubUsername);

    List<UserProfile> findByAutoTriageEnabledTrue();
}
