-- dianzan.blog definition

CREATE TABLE `blog` (
                        `id` bigint NOT NULL,
                        `user_id` bigint NOT NULL,
                        `title` varchar(512) DEFAULT NULL COMMENT '标题',
                        `content` text NOT NULL COMMENT '内容',
                        `thumb_count` int NOT NULL DEFAULT '0' COMMENT '点赞数',
                        `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                        `hot_score` int NOT NULL DEFAULT '0',
                        PRIMARY KEY (`id`),
                        KEY `idx_userId` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='博客表';


CREATE TABLE `thumb_0` (
                           `id` bigint NOT NULL,
                           `user_id` bigint NOT NULL,
                           `blog_id` bigint NOT NULL,
                           `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                           PRIMARY KEY (`id`),
                           UNIQUE KEY `idx_userId_blogId` (`user_id`,`blog_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='点赞表';


CREATE TABLE `user_follow_0` (
                                 `id` bigint NOT NULL,
                                 `user_id` bigint NOT NULL COMMENT '被关注者ID',
                                 `follower_id` bigint NOT NULL COMMENT '粉丝ID',
                                 `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
                                 `is_cancel` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否取消关注',
                                 PRIMARY KEY (`id`),
                                 UNIQUE KEY `uk_user_follower` (`user_id`,`follower_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='粉丝关系表';

CREATE TABLE `user` (
                        `id` bigint NOT NULL,
                        `user_account` varchar(255) NOT NULL COMMENT '账号',
                        `user_password` varchar(512) NOT NULL COMMENT '密码',
                        `user_name` varchar(255) DEFAULT NULL COMMENT '用户昵称',
                        `user_role` varchar(50) DEFAULT 'user' COMMENT '用户角色：user/admin',
                        `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                        `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
                        `last_login_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '最近一次登录时间',
                        `is_delete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除',
                        PRIMARY KEY (`id`),
                        UNIQUE KEY `uk_user_account` (`user_account`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表';