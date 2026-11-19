package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.PublishMeta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 发布元数据仓储：按 (script,user,platform) 三元组定位唯一一行，upsert 与归属校验都靠它 */
public interface PublishMetaRepository extends JpaRepository<PublishMeta, Long> {

    Optional<PublishMeta> findByScriptIdAndUserIdAndPlatform(Long scriptId, Long userId, String platform);
}
