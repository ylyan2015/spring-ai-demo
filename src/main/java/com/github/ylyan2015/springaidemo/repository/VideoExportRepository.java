package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.VideoExport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 视频导出记录数据访问层
 */
@Repository
public interface VideoExportRepository extends JpaRepository<VideoExport, Long> {

    /** 查询脚本的导出历史（最新在前） */
    List<VideoExport> findByScriptIdOrderByCreatedAtDesc(Long scriptId);

    /** 查询用户的所有导出记录 */
    List<VideoExport> findByUserIdOrderByCreatedAtDesc(Long userId);
}
