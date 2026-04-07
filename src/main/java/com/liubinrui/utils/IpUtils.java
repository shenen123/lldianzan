package com.liubinrui.utils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * IP工具类
 */
public class IpUtils {

    /**
     * 获取客户端真实IP
     */
    public static String getClientIp() {
        ServletRequestAttributes attributes = (ServletRequestAttributes)
                RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        return getClientIp(attributes.getRequest());
    }

    /**
     * 从Request中获取IP
     */
    public static String getClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }

        // 1. X-Forwarded-For（最常见，经过代理时）
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }

        // 2. X-Real-IP（Nginx常用）
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }

        // 3. Proxy-Client-IP
        ip = request.getHeader("Proxy-Client-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }

        // 4. WL-Proxy-Client-IP
        ip = request.getHeader("WL-Proxy-Client-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip;
        }

        // 5. 最后取 remoteAddr
        return request.getRemoteAddr();
    }

    /**
     * 判断IP是否内网
     */
    public static boolean isInternalIp(String ip) {
        if (ip == null) return true;

        return ip.startsWith("127.") ||
                ip.startsWith("192.168.") ||
                ip.startsWith("10.") ||
                ip.startsWith("172.16.") ||
                ip.startsWith("0:0:0:0:0:0:0:1");
    }
}
