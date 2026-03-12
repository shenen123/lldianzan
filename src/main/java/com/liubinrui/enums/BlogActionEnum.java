package com.liubinrui.enums;
public enum BlogActionEnum {
    LIKE(5),          // 点赞（权重5）
    COMMENT(3),       // 评论（权重3）
    FORWARD(4),       // 转发（权重4）
    COLLECT(2);       // 收藏（权重2）

    private final int weight;

    BlogActionEnum(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }
}
