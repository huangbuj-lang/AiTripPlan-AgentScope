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
