package com.liubinrui.controller;

import com.liubinrui.service.UserFollowService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/user/follow")
@Slf4j
public class UserFollowController {
    @Resource
    private UserFollowService userFollowService;

    /**
     * 关注用户接口
     * @param userId 被关注者ID（博主）
     * @param followerId 粉丝ID
     * @return 操作结果
     */
    @PostMapping("/add")
    public Map<String, Object> followUser(@RequestParam Long userId, @RequestParam Long followerId) {
        Map<String, Object> result = new HashMap<>();
        try {
            boolean success = userFollowService.followUser(userId, followerId);
            result.put("success", success);
            result.put("msg", success ? "关注成功" : "已关注，无需重复关注");
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", "关注失败：" + e.getMessage());
        }
        return result;
    }

    /**
     * 取消关注接口
     * @param userId 被关注者ID（博主）
     * @param followerId 粉丝ID
     * @return 操作结果
     */
    @PostMapping("/cancel")
    public Map<String, Object> cancelFollow(@RequestParam Long userId, @RequestParam Long followerId) {
        Map<String, Object> result = new HashMap<>();
        try {
            boolean success = userFollowService.cancelFollow(userId, followerId);
            result.put("success", success);
            result.put("msg", success ? "取消关注成功" : "未关注，无需取消");
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", "取消关注失败：" + e.getMessage());
        }
        return result;
    }

}
