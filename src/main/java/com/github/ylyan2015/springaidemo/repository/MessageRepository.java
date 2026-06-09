package com.github.ylyan2015.springaidemo.repository;

import com.github.ylyan2015.springaidemo.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 消息数据访问接口
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
    
    /**
     * 根据会话ID查询所有消息，按顺序排列
     */
    List<Message> findBySessionIdOrderByMessageOrderAsc(String sessionId);
    
    /**
     * 根据会话ID删除所有消息
     */
    void deleteBySessionId(String sessionId);
    
    /**
     * 统计会话中的消息数量
     */
    long countBySessionId(String sessionId);
}
