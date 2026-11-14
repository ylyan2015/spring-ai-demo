package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.VideoTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 视频模板数据访问层（模块 H）
 */
@Repository
public interface VideoTemplateRepository extends JpaRepository<VideoTemplate, Long> {

    /** 系统内置模板 + 指定用户的自定义模板 */
    List<VideoTemplate> findByIsSystemTrueOrUserIdOrderByIdAsc(Long userId);

    /** 全部系统内置模板（启动预置幂等检查用） */
    List<VideoTemplate> findByIsSystemTrue();
}