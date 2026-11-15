package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.SfxTrack;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 音效库仓库（Phase 4 切片 4）
 */
public interface SfxTrackRepository extends JpaRepository<SfxTrack, Long> {

    Optional<SfxTrack> findByFilePath(String filePath);

    List<SfxTrack> findByCategoryOrderByIdAsc(String category);
}