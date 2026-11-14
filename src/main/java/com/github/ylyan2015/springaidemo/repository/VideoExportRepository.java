package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.VideoExport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 视频导出记录数据访问层
 */
@Repository
public interface VideoExportRepository extends JpaRepository<VideoExport, Long> {

    /** 查询脚本的导出历史（最新在前） */
    List<VideoExport> findByScriptIdOrderByCreatedAtDesc(Long scriptId);

    /** 查询用户的所有导出记录 */
    List<VideoExport> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 最近一次成功导出（模块 I：封面生成缺省取最新成品视频） */
    Optional<VideoExport> findFirstByScriptIdAndUserIdAndStatusOrderByCreatedAtDesc(
            Long scriptId, Long userId, String status);

    /** 按 ID 定位并校验归属（封面生成可指定某次导出） */
    Optional<VideoExport> findByIdAndScriptIdAndUserId(Long id, Long scriptId, Long userId);

    /** 按 ID 定位并校验归属（批量打包下载，跨脚本选择导出记录） */
    Optional<VideoExport> findByIdAndUserId(Long id, Long userId);
}
