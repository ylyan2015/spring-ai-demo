package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.ImageRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 图像生成记录数据访问接口
 */
@Repository
public interface ImageRecordRepository extends JpaRepository<ImageRecord, Long> {

    /** 查询某用户的所有图像生成记录，按创建时间倒序 */
    List<ImageRecord> findByUserIdOrderByCreateTimeDesc(Long userId);

    /** 查询某用户在某个会话中的图像生成记录 */
    List<ImageRecord> findByUserIdAndSessionIdOrderByCreateTimeDesc(Long userId, String sessionId);
}
