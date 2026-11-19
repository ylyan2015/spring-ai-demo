package com.github.ylyan2015.springaidemo.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 发布元数据实体：一条稿件在某个平台上的投稿字段（标题/简介/标签/分区/声明/置顶评论）。
 *
 * <p>与 {@link VideoExport} 的分工：导出记录描述「文件长什么样」，本表描述「怎么发出去」。
 * 按 (script_id, user_id, platform) 唯一，同一稿件在 B 站只有一行，重复保存走 upsert 复用原行。
 *
 * <p>标签与时间戳用 CSV 而非 {@code @ElementCollection}：后者在 H2/PostgreSQL 上的序列与懒加载差异
 * 已在别处踩过，CSV 与 {@code StoryShot.characterIds} 保持同一套存储习惯。
 */
@Entity
@Table(name = "publish_meta",
        uniqueConstraints = @UniqueConstraint(name = "uk_publish_meta_scope",
                columnNames = {"script_id", "user_id", "platform"}))
public class PublishMeta {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "script_id", nullable = false)
    private Long scriptId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 平台名（PublishSpec.platform），当前仅 BILIBILI */
    @Column(name = "platform", nullable = false, length = 20)
    private String platform;

    /** 投稿标题（平台侧上限 80 字符，此处放宽以容纳多平台） */
    @Column(name = "title", length = 200)
    private String title;

    /** 简介正文，含【本片事实】/【素材出处】/【AI 使用说明】/【不构成投资建议】/【时间轴】等分节 */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 标签 CSV，已归一（去空去重保序） */
    @Column(name = "tags", length = 1000)
    private String tags;

    @Column(name = "category_name", length = 50)
    private String categoryName;

    /** 平台分区号（B 站 tid），灰度期可用投稿页地址参数 ?tid= 指定 */
    @Column(name = "category_tid")
    private Integer categoryTid;

    /** 创作声明，如「含AI生成内容」 */
    @Column(name = "declaration", length = 50)
    private String declaration;

    /** 合集/系列名，如「钱坑漫评」 */
    @Column(name = "collection", length = 100)
    private String collection;

    /** 置顶评论正文（发布后人工粘贴，平台无 API 代发） */
    @Column(name = "pinned_comment", columnDefinition = "TEXT")
    private String pinnedComment;

    /** 镜起点 CSV（秒，两位小数），与成片字幕/音轨床同一时间轴 */
    @Column(name = "timestamps_csv", length = 1000)
    private String timestampsCsv;

    /** MANUAL=人工编辑（派生与导出联动都不得覆盖非空字段）/ GENERATED=机器派生 */
    @Column(name = "source", nullable = false, length = 20)
    private String source = "MANUAL";

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public PublishMeta() {}

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getScriptId() { return scriptId; }
    public void setScriptId(Long scriptId) { this.scriptId = scriptId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public Integer getCategoryTid() { return categoryTid; }
    public void setCategoryTid(Integer categoryTid) { this.categoryTid = categoryTid; }

    public String getDeclaration() { return declaration; }
    public void setDeclaration(String declaration) { this.declaration = declaration; }

    public String getCollection() { return collection; }
    public void setCollection(String collection) { this.collection = collection; }

    public String getPinnedComment() { return pinnedComment; }
    public void setPinnedComment(String pinnedComment) { this.pinnedComment = pinnedComment; }

    public String getTimestampsCsv() { return timestampsCsv; }
    public void setTimestampsCsv(String timestampsCsv) { this.timestampsCsv = timestampsCsv; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
