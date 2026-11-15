package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.ShotCandidate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 镜头候选仓库（Phase 4 切片 3）
 */
public interface ShotCandidateRepository extends JpaRepository<ShotCandidate, Long> {

    List<ShotCandidate> findByShotIdOrderByIdAsc(Long shotId);

    Optional<ShotCandidate> findByIdAndShotId(Long id, Long shotId);

    void deleteByShotId(Long shotId);
}