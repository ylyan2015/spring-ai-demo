package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.StoryScript;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 故事脚本数据访问接口
 */
@Repository
public interface StoryScriptRepository extends JpaRepository<StoryScript, Long> {

    /**
     * 查找用户的所有脚本，按更新时间降序
     */
    List<StoryScript> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /**
     * 查找用户的指定脚本（确保归属权）
     */
    Optional<StoryScript> findByIdAndUserId(Long id, Long userId);

    /**
     * 删除用户的指定脚本
     */
    void deleteByIdAndUserId(Long id, Long userId);

    /**
     * 按状态查找脚本
     */
    List<StoryScript> findByUserIdAndStatusOrderByUpdatedAtDesc(Long userId, String status);
}
