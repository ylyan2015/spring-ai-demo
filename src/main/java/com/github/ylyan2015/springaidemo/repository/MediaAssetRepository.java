package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 素材库仓库（Phase 5 Track B Task 1）
 */
public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    List<MediaAsset> findByUserIdOrderByIdDesc(Long userId);
}