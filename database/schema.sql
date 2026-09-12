-- AiTripPlan-AgentScope 完整数据库结构（由代码+方案文档+data-pipeline 反推合并生成）
-- 目标库：aitripplan
CREATE DATABASE IF NOT EXISTS aitripplan CHARACTER SET utf8mb4;
USE aitripplan;

-- 1. 用户表
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名（唯一）',
    password VARCHAR(100) NOT NULL COMMENT '密码（BCrypt加密存储，非明文）',
    role VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT '角色(user/admin)',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '1正常 2注销 3封禁',
    pwd_version INT NOT NULL DEFAULT 0 COMMENT '改密/封禁+1，强制全端下线',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 2. 行程表
CREATE TABLE IF NOT EXISTS trip_plans (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '行程ID',
    user_id BIGINT NOT NULL COMMENT '所属用户ID（关联users.id）',
    city VARCHAR(100) COMMENT '城市',
    days INT COMMENT '天数',
    plan_content MEDIUMTEXT COMMENT '完整计划内容（markdown文本，最长16MB）',
    plan_json JSON NULL COMMENT '结构化 TripPlanData 契约',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='旅行计划表';

-- 3. 个人资料
CREATE TABLE IF NOT EXISTS user_profiles (
  user_id    BIGINT PRIMARY KEY COMMENT '关联 users.id（1:1）',
  nickname   VARCHAR(50)  NULL,
  avatar_url VARCHAR(255) NULL,
  email      VARCHAR(100) NULL COMMENT '邮箱可空，非空才唯一',
  bio        VARCHAR(200) NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. 出行偏好
CREATE TABLE IF NOT EXISTS user_preferences (
  user_id      BIGINT PRIMARY KEY,
  travel_style JSON NULL,
  budget_level TINYINT NULL COMMENT '1经济 2舒适 3奢华',
  pace         TINYINT NULL COMMENT '1紧凑 2适中 3松弛',
  avoid_list   JSON NULL COMMENT '忌口/回避项',
  use_by_default TINYINT NOT NULL DEFAULT 1,
  updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 5. 用户画像标签（data-pipeline/profile.py 生成）
CREATE TABLE IF NOT EXISTS user_profiles_tags (
  user_id BIGINT PRIMARY KEY,
  tags JSON,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 6. 行程分享
CREATE TABLE IF NOT EXISTS trip_shares (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  trip_id       BIGINT NOT NULL,
  user_id       BIGINT NOT NULL,
  share_code    VARCHAR(16) NOT NULL,
  password_hash VARCHAR(100) NULL,
  is_public     TINYINT NOT NULL DEFAULT 1,
  expires_at    DATETIME NULL,
  view_count    INT NOT NULL DEFAULT 0,
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_share_code (share_code),
  UNIQUE KEY uk_trip (trip_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 7. 论坛版块
CREATE TABLE IF NOT EXISTS forum_boards (
  id          INT PRIMARY KEY AUTO_INCREMENT,
  name        VARCHAR(30) NOT NULL,
  description VARCHAR(200) NULL,
  sort_order  INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 8. 帖子
CREATE TABLE IF NOT EXISTS posts (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id        BIGINT NOT NULL,
  board_id       INT NOT NULL,
  title          VARCHAR(100) NOT NULL,
  content        MEDIUMTEXT NOT NULL,
  trip_id        BIGINT NULL,
  trip_plan_json JSON NULL,
  travel_date    DATE NULL,
  destination    VARCHAR(50) NULL,
  ai_answer_post_id BIGINT NULL COMMENT 'AI答主生成的回答帖',
  view_count     INT NOT NULL DEFAULT 0,
  like_count     INT NOT NULL DEFAULT 0,
  comment_count  INT NOT NULL DEFAULT 0,
  status         TINYINT NOT NULL DEFAULT 1 COMMENT '0待审 1正常 2隐藏',
  created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FULLTEXT KEY ft_content (title, content) WITH PARSER ngram,
  KEY idx_board_time (board_id, created_at),
  KEY idx_destination (destination, travel_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 9. 评论（两级楼中楼）
CREATE TABLE IF NOT EXISTS comments (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  post_id       BIGINT NOT NULL,
  user_id       BIGINT NOT NULL,
  parent_id     BIGINT NULL,
  reply_user_id BIGINT NULL,
  content       TEXT NOT NULL,
  status        TINYINT NOT NULL DEFAULT 1,
  is_ai         TINYINT NOT NULL DEFAULT 0 COMMENT 'AI回答',
  is_adopted    TINYINT NOT NULL DEFAULT 0 COMMENT '被采纳',
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_post (post_id, created_at),
  KEY idx_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 10. 点赞 / 收藏
CREATE TABLE IF NOT EXISTS post_likes (
  user_id BIGINT, post_id BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, post_id), KEY idx_post (post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS post_favorites (
  user_id BIGINT, post_id BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, post_id), KEY idx_post (post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 11. 标签
CREATE TABLE IF NOT EXISTS tags (
  id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(30) NOT NULL,
  UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS post_tags (
  post_id BIGINT, tag_id INT,
  PRIMARY KEY (post_id, tag_id), KEY idx_tag (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 12. 审核记录
CREATE TABLE IF NOT EXISTS moderation_records (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  target_type TINYINT NOT NULL,
  target_id   BIGINT NOT NULL,
  verdict     TINYINT NOT NULL,
  reason      VARCHAR(200) NULL,
  model       VARCHAR(50) NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 13. 举报
CREATE TABLE IF NOT EXISTS reports (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  reporter_id BIGINT NOT NULL,
  target_type TINYINT NOT NULL,
  target_id   BIGINT NOT NULL,
  reason      VARCHAR(200) NOT NULL,
  status      TINYINT NOT NULL DEFAULT 0,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 14. 通知
CREATE TABLE IF NOT EXISTS notifications (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id    BIGINT NOT NULL,
  type       TINYINT NOT NULL,
  content    VARCHAR(200) NOT NULL,
  link_url   VARCHAR(255) NULL,
  is_read    TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_user_read (user_id, is_read, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 15. 公告
CREATE TABLE IF NOT EXISTS announcements (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  title      VARCHAR(100) NOT NULL,
  content    TEXT NOT NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 16. POI 基础表（data-pipeline/collect.py）
CREATE TABLE IF NOT EXISTS attractions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  city VARCHAR(50),
  address VARCHAR(255),
  lng DECIMAL(10,6), lat DECIMAL(10,6),
  types VARCHAR(200),
  rating DECIMAL(3,1),
  cost VARCHAR(50),
  source VARCHAR(20) DEFAULT 'amap',
  UNIQUE KEY uk_name_city (name, city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS foods (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  city VARCHAR(50),
  address VARCHAR(255),
  lng DECIMAL(10,6), lat DECIMAL(10,6),
  types VARCHAR(200),
  rating DECIMAL(3,1),
  cost VARCHAR(50),
  source VARCHAR(20) DEFAULT 'amap',
  UNIQUE KEY uk_name_city (name, city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS hotels (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  city VARCHAR(50),
  address VARCHAR(255),
  lng DECIMAL(10,6), lat DECIMAL(10,6),
  types VARCHAR(200),
  rating DECIMAL(3,1),
  cost VARCHAR(50),
  source VARCHAR(20) DEFAULT 'amap',
  UNIQUE KEY uk_name_city (name, city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 17. POI 扩展表（data-pipeline/preprocess.py 统一结构）
CREATE TABLE IF NOT EXISTS attractions_features (
  src_id BIGINT, name VARCHAR(100), city VARCHAR(50), types VARCHAR(200),
  rating DECIMAL(3,1), label VARCHAR(30), level VARCHAR(10), cost VARCHAR(50),
  address VARCHAR(255), ticket_price VARCHAR(50), is_free TINYINT, visit_duration VARCHAR(50),
  scenic_grade VARCHAR(10), intro VARCHAR(500), booking_required VARCHAR(100),
  suitable_tags VARCHAR(100), tips VARCHAR(500), best_time VARCHAR(255), source VARCHAR(20) DEFAULT 'rule',
  UNIQUE KEY uk_name_city (name, city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS foods_features (
  src_id BIGINT, name VARCHAR(100), city VARCHAR(50), types VARCHAR(200),
  rating DECIMAL(3,1), label VARCHAR(30), level VARCHAR(10), cost VARCHAR(50),
  address VARCHAR(255), ticket_price VARCHAR(50), is_free TINYINT, visit_duration VARCHAR(50),
  scenic_grade VARCHAR(10), intro VARCHAR(500), booking_required VARCHAR(100),
  suitable_tags VARCHAR(100), tips VARCHAR(500), best_time VARCHAR(255), source VARCHAR(20) DEFAULT 'rule',
  UNIQUE KEY uk_name_city (name, city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS hotels_features (
  src_id BIGINT, name VARCHAR(100), city VARCHAR(50), types VARCHAR(200),
  rating DECIMAL(3,1), label VARCHAR(30), level VARCHAR(10), cost VARCHAR(50),
  address VARCHAR(255), ticket_price VARCHAR(50), is_free TINYINT, visit_duration VARCHAR(50),
  scenic_grade VARCHAR(10), intro VARCHAR(500), booking_required VARCHAR(100),
  suitable_tags VARCHAR(100), tips VARCHAR(500), best_time VARCHAR(255), source VARCHAR(20) DEFAULT 'rule',
  UNIQUE KEY uk_name_city (name, city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 18. 客流预测（data-pipeline/forecast.py）
CREATE TABLE IF NOT EXISTS forecast_output (
  city VARCHAR(50), day DATE, forecast_idx DECIMAL(6,2), PRIMARY KEY(city, day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 19. 定价（data-pipeline/pricing.py）
CREATE TABLE IF NOT EXISTS holidays (
  day DATE PRIMARY KEY, name VARCHAR(20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS pricing_output (
  city VARCHAR(50) PRIMARY KEY,
  hotel_base DECIMAL(8,1), hotel_price DECIMAL(8,1),
  ticket_base DECIMAL(8,1), ticket_price DECIMAL(8,1),
  food_base DECIMAL(8,1) NULL, food_price DECIMAL(8,1) NULL,
  factors JSON,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;



-- ============================================================
-- ★补全：运行时/观测类表（原 schema.sql 漏写；2026-09-12 从实际库 SHOW CREATE TABLE 导出）
--   缺这几张会导致换电脑照本文件建库后，任务历史/事件/链路/成本统计等功能报错
-- ============================================================

CREATE TABLE IF NOT EXISTS `llm_cost_daily` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `day` date NOT NULL,
  `dimension` varchar(20) NOT NULL,
  `calls` int NOT NULL DEFAULT '0',
  `input_tokens` bigint NOT NULL DEFAULT '0',
  `output_tokens` bigint NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_day_dim` (`day`,`dimension`)
) ENGINE=InnoDB AUTO_INCREMENT=483 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='LLMɱάȻ';

CREATE TABLE IF NOT EXISTS `plan_feedback` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL DEFAULT '0',
  `plan_id` bigint DEFAULT NULL,
  `action` varchar(20) NOT NULL,
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `task_events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(64) NOT NULL,
  `ts` bigint NOT NULL,
  `type` varchar(32) NOT NULL DEFAULT '',
  `content` text,
  PRIMARY KEY (`id`),
  KEY `idx_session` (`session_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `task_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(80) NOT NULL,
  `user_id` bigint NOT NULL DEFAULT '0',
  `status` varchar(20) NOT NULL,
  `current_step` varchar(100) NOT NULL DEFAULT '',
  `submit_ms` bigint NOT NULL,
  `last_active_ms` bigint NOT NULL,
  `model_calls_used` int NOT NULL DEFAULT '0',
  `prompt` text,
  PRIMARY KEY (`id`),
  UNIQUE KEY `session_id` (`session_id`),
  KEY `idx_submit` (`submit_ms`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=138 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ʷ(۲)';

CREATE TABLE IF NOT EXISTS `task_obs` (
  `session_id` varchar(64) NOT NULL,
  `obs_json` text,
  `updated_ms` bigint NOT NULL,
  PRIMARY KEY (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS `trace_span` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` varchar(80) NOT NULL,
  `agent` varchar(20) NOT NULL DEFAULT '',
  `layer` varchar(10) NOT NULL,
  `name` varchar(80) NOT NULL,
  `detail` text,
  `success` tinyint NOT NULL DEFAULT '1',
  `cost_ms` bigint NOT NULL DEFAULT '-1',
  `ts` bigint NOT NULL,
  `attempts` text,
  PRIMARY KEY (`id`),
  KEY `idx_sid` (`session_id`),
  KEY `idx_ts` (`ts`)
) ENGINE=InnoDB AUTO_INCREMENT=15099 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='ϸ(۲)';
