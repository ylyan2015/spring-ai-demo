package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.Bgm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 背景音乐数据访问层
 */
@Repository
public interface BgmRepository extends JpaRepository<Bgm, Long> {

    /** 查询所有内置音乐 */
    List<Bgm> findByIsBuiltInTrue();

    /** 按分类查询内置音乐 */
    List<Bgm> findByIsBuiltInTrueAndCategory(String category);

    /** 按文件路径查找内置音乐（音乐库扫描去重用） */
    Optional<Bgm> findByFilePathAndIsBuiltInTrue(String filePath);

    /** 查询内置音乐 + 指定用户上传的音乐 */
    List<Bgm> findByIsBuiltInTrueOrUserIdOrderByCreatedAtDesc(Boolean isBuiltIn, Long userId);

    /** 查询指定用户上传的音乐 */
    List<Bgm> findByUserIdOrderByCreatedAtDesc(Long userId);
}
