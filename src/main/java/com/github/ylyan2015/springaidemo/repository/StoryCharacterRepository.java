package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.StoryCharacter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 角色卡仓库（Phase 4 切片 1）
 */
public interface StoryCharacterRepository extends JpaRepository<StoryCharacter, Long> {

    List<StoryCharacter> findByScriptIdOrderByIdAsc(Long scriptId);

    Optional<StoryCharacter> findByIdAndScriptId(Long id, Long scriptId);

    void deleteByScriptId(Long scriptId);
}