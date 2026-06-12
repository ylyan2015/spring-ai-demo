package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.ModelParamPreset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelParamPresetRepository extends JpaRepository<ModelParamPreset, Long> {

    /** 查询某用户对某模型的参数预设 */
    Optional<ModelParamPreset> findByUserIdAndModelKey(Long userId, String modelKey);

    /** 查询某用户所有模型的参数预设 */
    List<ModelParamPreset> findByUserId(Long userId);
}
