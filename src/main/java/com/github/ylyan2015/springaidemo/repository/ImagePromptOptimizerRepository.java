package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.ImagePromptOptimizer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 图像提示词优化记录数据访问接口
 */
@Repository
public interface ImagePromptOptimizerRepository extends JpaRepository<ImagePromptOptimizer, Long> {

    /**
     * 查找用户的所有优化记录，按创建时间降序
     */
    List<ImagePromptOptimizer> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 查找用户对某个脚本的优化记录
     */
    List<ImagePromptOptimizer> findByUserIdAndScriptIdOrderByCreatedAtDesc(Long userId, Long scriptId);

    /**
     * 查找用户对某个镜头的优化记录
     */
    List<ImagePromptOptimizer> findByUserIdAndShotIdOrderByCreatedAtDesc(Long userId, Long shotId);

    /**
     * 按状态查找优化记录
     */
    List<ImagePromptOptimizer> findByUserIdAndStatusOrderByCreatedAtDesc(Long userId, String status);
}
