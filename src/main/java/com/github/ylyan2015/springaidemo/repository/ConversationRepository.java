package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 会话数据访问接口
 */
@Repository
public interface ConversationRepository extends JpaRepository<Conversation, Long> {
    
    /**
     * 根据会话ID查找会话
     */
    Optional<Conversation> findBySessionId(String sessionId);
    
    /**
     * 根据会话ID删除会话
     */
    void deleteBySessionId(String sessionId);

    /**
     * 查找用户的所有会话，按更新时间降序
     */
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
