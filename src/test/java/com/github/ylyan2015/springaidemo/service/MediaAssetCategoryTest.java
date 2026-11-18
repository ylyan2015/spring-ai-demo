package com.github.ylyan2015.springaidemo.service;

import com.github.ylyan2015.springaidemo.entity.MediaAsset;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 素材库切片 S1 Task 1：category 列 + 存量兼容语义（null/空白 → INSERT，Track B 行为逐字不变）
 */
class MediaAssetCategoryTest {

    @Test
    void nullCategoryMeansInsert() {
        MediaAsset a = new MediaAsset();
        assertEquals("INSERT", a.effectiveCategory());
        a.setCategory("  ");
        assertEquals("INSERT", a.effectiveCategory());
    }

    @Test
    void explicitCategoryPreserved() {
        MediaAsset a = new MediaAsset();
        a.setCategory("CHARACTER");
        assertEquals("CHARACTER", a.effectiveCategory());
        a.setCategory("SCENE");
        assertEquals("SCENE", a.effectiveCategory());
    }
}
