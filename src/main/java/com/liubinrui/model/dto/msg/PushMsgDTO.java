package com.liubinrui.model.dto.msg;

import lombok.Data;

@Data
public class PushMsgDTO {
    private Long blogId;

    private Long senderId;

    private Long timestamp;    // 发布时间戳
    // 转字符串用于Redis传输
    public String toRedisString() {
        return senderId + ":" + blogId + ":" + timestamp;
    }

    // 从字符串解析
    public static PushMsgDTO fromRedisString(String str) {
        String[] parts = str.split(":");
        PushMsgDTO dto = new PushMsgDTO();
        dto.setSenderId(Long.parseLong(parts[0]));
        dto.setBlogId(Long.parseLong(parts[1]));
        dto.setTimestamp(Long.parseLong(parts[2]));
        return dto;
    }
}
