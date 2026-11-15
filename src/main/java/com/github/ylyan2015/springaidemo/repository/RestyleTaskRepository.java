package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.RestyleTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 视频转绘任务仓库（Phase 4 切片 5）
 */
public interface RestyleTaskRepository extends JpaRepository<RestyleTask, Long> {

    List<RestyleTask> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<RestyleTask> findByIdAndUserId(Long id, Long userId);
}