package com.github.ylyan2015.springaidemo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话实体类
 * 代表一次完整的对话会话
 */
@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 所属用户ID
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 会话唯一标识（UUID）
     */
    @Column(name = "session_id", unique = true, nullable = false)
    private String sessionId;

    /**
     * 会话标题（可选，可由第一条消息生成）
     */
    @Column(name = "title")
    private String title;

    /**
     * 创建时间
     */
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /**
     * 最后更新时间
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 关联的消息列表
     */
    @OneToMany(mappedBy = "sessionId", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("messageOrder ASC")
    private List<Message> messages = new ArrayList<>();

    public Conversation() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Conversation(String sessionId) {
        this.sessionId = sessionId;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Conversation(String sessionId, String title) {
        this.sessionId = sessionId;
        this.title = title;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Conversation(String sessionId, Long userId) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 添加消息到会话
     */
    public void addMessage(Message message) {
        this.messages.add(message);
        this.updatedAt = LocalDateTime.now();
    }
}
