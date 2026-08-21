-- ============================================
-- AiTripPlan-AgentScope 数据库初始化脚本
-- 用途：让项目（登录注册 + 行程保存）正常运行
-- 执行方式：在 MySQL 客户端执行本文件（需先有连接权限）
-- ============================================

-- 1. 创建数据库
CREATE DATABASE IF NOT EXISTS aitripplan DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE aitripplan;

-- 2. 用户表（登录注册用，LoginController 操作）
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名（唯一）',
    password VARCHAR(100) NOT NULL COMMENT '密码（BCrypt加密存储，非明文）',
    role VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT '角色(user/admin)',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '账号状态(1正常 2注销 3封禁)',
    pwd_version INT NOT NULL DEFAULT 0 COMMENT '凭证版本(改密/封禁+1，强制全端下线)',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ★已有数据库升级（老库缺少新列时执行下面这些 ALTER，再指定管理员）：
-- ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT '角色(user/admin)';
-- ALTER TABLE users ADD COLUMN status TINYINT NOT NULL DEFAULT 1 COMMENT '账号状态(1正常 2注销 3封禁)';
-- ALTER TABLE users ADD COLUMN pwd_version INT NOT NULL DEFAULT 0 COMMENT '凭证版本(改密/封禁+1)';
-- ALTER TABLE users ADD COLUMN create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间';
-- 指定管理员示例：UPDATE users SET role='admin' WHERE username='你的管理员用户名';

-- ★已有数据库升级（老库没有 role 列时执行下面这句，再指定管理员）：
-- ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT '角色(user/admin)';
-- 指定管理员示例：UPDATE users SET role='admin' WHERE username='你的管理员用户名';

-- 3. 行程表（保存行程用，PlanController 操作）
-- ★plan_content 必须是 MEDIUMTEXT：TEXT 只有64KB，存不下完整的长行程（会保存失败500）
CREATE TABLE IF NOT EXISTS trip_plans (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '行程ID',
    user_id BIGINT NOT NULL COMMENT '所属用户ID（关联users.id）',
    city VARCHAR(100) COMMENT '城市',
    days INT COMMENT '天数',
    plan_content MEDIUMTEXT COMMENT '完整计划内容（markdown文本，最长16MB）',
    plan_json JSON NULL COMMENT '结构化 TripPlanData 契约（供分享/复制/二次编辑）',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='旅行计划表';

-- 2.5 用户资料表（1:1 关联 users，展示资料与认证核心分离；个人中心扩展用）
CREATE TABLE IF NOT EXISTS user_profiles (
    user_id BIGINT PRIMARY KEY COMMENT '关联users.id（1:1）',
    nickname VARCHAR(50) NULL COMMENT '昵称',
    avatar_url VARCHAR(255) NULL COMMENT '头像URL',
    email VARCHAR(100) NULL COMMENT '邮箱（可空，非空才唯一）',
    bio VARCHAR(200) NULL COMMENT '个人简介',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户资料表';

-- 3. 行程表（保存行程用，PlanController 操作）
-- ★plan_content 必须是 MEDIUMTEXT：TEXT 只有64KB，存不下完整的长行程（会保存失败500）
CREATE TABLE IF NOT EXISTS trip_plans (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '行程ID',
    user_id BIGINT NOT NULL COMMENT '所属用户ID（关联users.id）',
    city VARCHAR(100) COMMENT '城市',
    days INT COMMENT '天数',
    plan_content MEDIUMTEXT COMMENT '完整计划内容（markdown文本，最长16MB）',
    plan_json JSON NULL COMMENT '结构化 TripPlanData 契约（供分享/复制/二次编辑）',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='旅行计划表';

-- 3.5 用户旅行偏好（个人中心设置，注入 TripRequest 用）
-- ★两版本偏好列并集：预检卡(walk_tolerance/chronotype/museum_ratio/preferred_style) + 个人中心沉淀(travel_style/budget_level/pace/avoid_list/use_by_default)
CREATE TABLE IF NOT EXISTS user_preferences (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
    user_id BIGINT NOT NULL UNIQUE COMMENT '关联users.id',
    walk_tolerance VARCHAR(20) NULL COMMENT '步行忍耐度(米)',
    chronotype VARCHAR(20) NULL COMMENT '作息 early/both/night',
    museum_ratio VARCHAR(20) NULL COMMENT '博物馆占比 low/mid/high',
    preferred_style VARCHAR(20) NULL COMMENT '首选风格',
    travel_style  JSON NULL COMMENT '风格标签 ["穷游","亲子","美食"]',
    budget_level  TINYINT NULL COMMENT '预算档 1经济 2舒适 3奢华',
    pace          TINYINT NULL COMMENT '节奏 1紧凑 2适中 3松弛',
    avoid_list    JSON NULL COMMENT '忌口/回避项',
    use_by_default TINYINT NOT NULL DEFAULT 1 COMMENT '是否默认注入到每次生成',
    update_time   TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户旅行偏好表';

-- 3.6 行程分享（只读链接，免登录查看）
CREATE TABLE IF NOT EXISTS trip_shares (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    trip_id       BIGINT NOT NULL COMMENT '关联trip_plans.id',
    user_id       BIGINT NOT NULL COMMENT '分享者（关联users.id）',
    share_code    VARCHAR(16) NOT NULL COMMENT '短码',
    password_hash VARCHAR(100) NULL COMMENT '可选访问密码(BCrypt)',
    is_public     TINYINT NOT NULL DEFAULT 1 COMMENT '是否公开',
    expires_at    DATETIME NULL COMMENT '过期时间',
    view_count    INT NOT NULL DEFAULT 0 COMMENT '浏览量',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_share_code (share_code),
    UNIQUE KEY uk_trip (trip_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行程分享表';

-- ============================================
-- 4. 论坛模块（阶段二 P1，ForumController 操作）
-- ============================================

-- 4.1 版块（攻略/问答/结伴三版块）
CREATE TABLE IF NOT EXISTS forum_boards (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '版块ID',
    name       VARCHAR(50) NOT NULL COMMENT '版块名',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序（小在前）'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论坛版块表';

-- 初始版块（幂等）
INSERT IGNORE INTO forum_boards (id, name, sort_order) VALUES
    (1, '旅行攻略', 1),
    (2, '旅行问答', 2),
    (3, '结伴同行', 3);

-- 4.2 帖子
CREATE TABLE IF NOT EXISTS posts (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '帖子ID',
    user_id        BIGINT NOT NULL COMMENT '作者（关联users.id）',
    board_id       BIGINT NOT NULL COMMENT '所属版块',
    title          VARCHAR(120) NOT NULL COMMENT '标题',
    content        MEDIUMTEXT NOT NULL COMMENT '正文（markdown 原文）',
    trip_id        BIGINT NULL COMMENT '关联行程（攻略内嵌行程）',
    trip_plan_json JSON NULL COMMENT '内嵌结构化行程（供详情展示/同款复制）',
    destination    VARCHAR(100) NULL COMMENT '目的地（结伴版块）',
    travel_date    VARCHAR(50) NULL COMMENT '出行时间（结伴版块）',
    like_count     INT NOT NULL DEFAULT 0 COMMENT '点赞数',
    comment_count  INT NOT NULL DEFAULT 0 COMMENT '评论数',
    view_count     INT NOT NULL DEFAULT 0 COMMENT '浏览量',
    status         TINYINT NOT NULL DEFAULT 1 COMMENT '1正常 0隐藏(审核/删除)',
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_board (board_id),
    INDEX idx_user (user_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论坛帖子表';

-- 4.3 评论（两级楼中楼：parent_id 指向被回复评论）
CREATE TABLE IF NOT EXISTS comments (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '评论ID',
    post_id       BIGINT NOT NULL COMMENT '所属帖子',
    user_id       BIGINT NOT NULL COMMENT '评论者（AI答主为0）',
    parent_id     BIGINT NULL COMMENT '父评论（楼中楼）',
    reply_user_id BIGINT NULL COMMENT '被回复的用户ID',
    content       VARCHAR(1000) NOT NULL COMMENT '评论内容',
    is_ai         TINYINT NOT NULL DEFAULT 0 COMMENT '1=AI答主生成',
    is_adopted    TINYINT NOT NULL DEFAULT 0 COMMENT '1=楼主已采纳',
    status        TINYINT NOT NULL DEFAULT 1 COMMENT '1正常 0删除',
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_post (post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='论坛评论表';

-- 4.4 点赞 / 收藏（用户-帖子关系）
CREATE TABLE IF NOT EXISTS post_likes (
    user_id    BIGINT NOT NULL,
    post_id    BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子点赞表';

CREATE TABLE IF NOT EXISTS post_favorites (
    user_id    BIGINT NOT NULL,
    post_id    BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='帖子收藏表';

-- 4.5 通知（评论/点赞/收藏时通知作者，SSE 实时推送）
CREATE TABLE IF NOT EXISTS notifications (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '通知ID',
    user_id    BIGINT NOT NULL COMMENT '接收者',
    type       TINYINT NOT NULL COMMENT '1评论 2点赞 3收藏 4系统 5公告',
    content    VARCHAR(255) NOT NULL COMMENT '通知文案',
    link_url   VARCHAR(255) NULL COMMENT '跳转链接',
    is_read    TINYINT NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='通知表';

-- 4.6 审核记录（AI 审核 Agent 结构化输出落库，阶段三）
CREATE TABLE IF NOT EXISTS moderation_records (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '审核记录ID',
    target_type TINYINT NOT NULL COMMENT '1帖子 2评论',
    target_id   BIGINT NOT NULL COMMENT '被审核内容ID',
    verdict     TINYINT NOT NULL COMMENT '0pass 1review 2block',
    reason      VARCHAR(200) NULL COMMENT '审核理由',
    model       VARCHAR(50) NULL COMMENT '审核模型（降级可追溯）',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内容审核记录表';

-- 4.7 举报（用户举报进管理员审核队列，阶段三）
CREATE TABLE IF NOT EXISTS reports (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '举报ID',
    reporter_id BIGINT NOT NULL COMMENT '举报者',
    target_type TINYINT NOT NULL COMMENT '1帖子 2评论',
    target_id   BIGINT NOT NULL COMMENT '被举报内容ID',
    reason      VARCHAR(200) NOT NULL COMMENT '举报理由',
    status      TINYINT NOT NULL DEFAULT 0 COMMENT '0待处理 1已处理',
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='举报表';

-- 4.8 公告（管理后台发布，联动全员通知，阶段三）
CREATE TABLE IF NOT EXISTS announcements (
    id         BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '公告ID',
    title      VARCHAR(100) NOT NULL COMMENT '标题',
    content    TEXT NOT NULL COMMENT '正文',
    created_by BIGINT NOT NULL COMMENT '发布管理员',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告表';