package com.bot.bot.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PrAnalysisRepository extends JpaRepository<PrAnalysis, Long> {

    @Query("SELECT p FROM PrAnalysis p WHERE p.owner = :owner AND p.repo = :repo AND p.prNumber = :prNumber ORDER BY p.createdAt DESC LIMIT 1")
    Optional<PrAnalysis> findLatest(@Param("owner") String owner, @Param("repo") String repo, @Param("prNumber") int prNumber);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM PrAnalysis p WHERE p.owner = :owner AND p.repo = :repo AND p.prNumber = :prNumber AND p.commitSha = :commitSha")
    boolean existsByOwnerRepoPrSha(@Param("owner") String owner, @Param("repo") String repo, @Param("prNumber") int prNumber, @Param("commitSha") String commitSha);

    List<PrAnalysis> findByCreatedAtAfter(@Param("since") Instant since);

    @Query("SELECT p FROM PrAnalysis p WHERE LOWER(COALESCE(p.targetUser, '')) = LOWER(:user) OR LOWER(p.owner) = LOWER(:user) ORDER BY p.createdAt DESC")
    List<PrAnalysis> findByUser(@Param("user") String user, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT p FROM PrAnalysis p WHERE LOWER(COALESCE(p.targetUser, '')) = LOWER(:user) OR LOWER(p.owner) = LOWER(:user)")
    List<PrAnalysis> findAllByUser(@Param("user") String user);

    @Query("SELECT COUNT(p) FROM PrAnalysis p WHERE LOWER(p.owner) = LOWER(:owner) AND LOWER(p.repo) = LOWER(:repo) AND LOWER(COALESCE(p.author, '')) = LOWER(:author) AND p.prNumber != :prNumber")
    long countPriorPrsByAuthor(@Param("owner") String owner, @Param("repo") String repo, @Param("author") String author, @Param("prNumber") int prNumber);

    @Query("SELECT COUNT(p) FROM PrAnalysis p WHERE LOWER(p.owner) = LOWER(:owner) AND LOWER(p.repo) = LOWER(:repo) AND LOWER(COALESCE(p.author, '')) = LOWER(:author)")
    long countTotalPrsByAuthor(@Param("owner") String owner, @Param("repo") String repo, @Param("author") String author);
}
