package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.StoryShot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 故事镜头数据访问接口
 */
@Repository
public interface StoryShotRepository extends JpaRepository<StoryShot, Long> {

    /**
     * 查找脚本的所有镜头，按顺序排列
     * （scriptId 非持久化属性，仅为 getScriptId() 派生 getter，须用 JPQL 走 script.id 关联）
     */
    @Query("SELECT s FROM StoryShot s WHERE s.script.id = :scriptId ORDER BY s.shotOrder ASC")
    List<StoryShot> findByScriptIdOrderByShotOrderAsc(@Param("scriptId") Long scriptId);

    /**
     * 查找脚本中的最大镜头顺序号
     */
    @Query("SELECT MAX(s.shotOrder) FROM StoryShot s WHERE s.script.id = :scriptId")
    Optional<Integer> findMaxShotOrderByScriptId(@Param("scriptId") Long scriptId);

    /**
     * 删除脚本的所有镜头
     */
    @Modifying
    @Query("DELETE FROM StoryShot s WHERE s.script.id = :scriptId")
    void deleteByScriptId(@Param("scriptId") Long scriptId);

    /**
     * 查找指定镜头（确保归属权）
     */
    @Query("SELECT s FROM StoryShot s WHERE s.id = :id AND s.script.id = :scriptId")
    Optional<StoryShot> findByIdAndScriptId(@Param("id") Long id, @Param("scriptId") Long scriptId);
}
