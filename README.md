# 🚀 高并发博客点赞系统 (High-Concurrency Blog Thumb System)

> **基于 Spring Cloud Alibaba 架构，融合 HeavyKeeper 热点检测、两级缓存、分库分表与消息队列的企业级点赞解决方案。**
> 
> 支持百万级 QPS 流量冲击，实现毫秒级响应，保证数据最终一致性，具备完整的可观测性与容灾能力。

---

## 📖 1. 项目介绍 (Project Introduction)

### 1.1 背景与挑战
在内容社区、社交平台中，“点赞”是最高频的交互行为之一。随着用户量增长，传统单体架构面临严峻挑战：
- **瞬时流量洪峰**：热门博文发布时，QPS 瞬间飙升，数据库连接池易爆满。
- **热点数据倾斜**：20% 的博文承载 80% 的点赞流量，导致单节点 Redis/DB 负载过高。
- **数据一致性难题**：高并发下容易出现“超卖”（点赞数虚高）或“少算”（丢失点赞）。
- **用户体验敏感**：点赞操作需毫秒级响应，任何延迟都会降低用户互动意愿。

### 1.2 核心目标
本项目旨在构建一个**高可用、高并发、可扩展**的点赞系统，核心指标：
- ✅ **高性能**：平均响应时间 < 50ms，支持 10w+ QPS。
- ✅ **高一致**：点赞状态强一致，点赞数最终一致（误差 < 1s）。
- ✅ **高可用**：服务可用性 99.99%，具备自动熔断、降级能力。
- ✅ **可观测**：全链路监控，实时感知系统健康度。

### 1.3 核心功能
- ✨ **极速点赞/取消**：基于 Redis + 分布式锁，防重防抖。
- 🔥 **热点自动识别**：集成 **HeavyKeeper 算法**，实时发现 TopK 热门博文，自动触发多级缓存策略。
- 📊 **异步计数同步**：通过 **RabbitMQ** 削峰填谷，异步更新 DB 点赞数，解耦核心链路。
- 🛡️ **流量防护**：集成 **Sentinel**，实现接口限流、熔断降级，防止雪崩。
- 🗄️ **海量数据存储**：基于 **ShardingSphere** 实现点赞记录的分库分表，支撑亿级数据量。
- 📈 **实时监控大屏**：基于 **Prometheus + Grafana**，可视化展示 QPS、延迟、错误率等核心指标。

---

## 🛠️ 2. 技术选型 (Technology Stack)

本项目采用主流且成熟的技术栈，确保系统的高性能与稳定性：

| 模块 | 技术栈 | 选型理由 |
| :--- | :--- | :--- |
| **核心框架** | `Spring Boot 3.x` + `Spring Cloud Alibaba` | 主流微服务生态，集成度高，社区活跃，完美支持云原生。 |
| **服务注册/配置** | **Nacos** | 集注册中心与配置中心于一体，支持配置动态刷新，简化运维。 |
| **流量防护** | **Sentinel** | 阿里开源，功能强大，支持接口限流、熔断降级、系统自适应保护。 |
| **缓存中间件** | **Redis 7.x** (`Redisson`) | 高性能 KV 存储，Redisson 提供分布式锁、Map 等高级对象，简化并发控制。 |
| **消息队列** | **RabbitMQ** | 轻量级，可靠性高，适合异步解耦点赞计数场景，支持消息确认机制。 |
| **热点检测** | **HeavyKeeper** (自研集成) | 相比 Count-Min Sketch 更精准，内存占用低，专为 TopK 热点设计，能自动衰减非热点数据。 |
| **多级缓存** | `Caffeine` (本地) + `Redis` (分布式) | 两级缓存架构：热点数据本地命中（纳秒级），减少网络 IO；冷数据走 Redis。 |
| **数据库** | `MySQL 8.0` + **ShardingSphere-JDBC** | 成熟关系型数据库，通过分库分表解决单表数据量瓶颈，支持在线扩容。 |
| **搜索引擎** | **ElasticSearch 8.x** | 用于点赞记录的复杂查询、日志分析及多维统计报表。 |
| **监控告警** | **Prometheus** + **Grafana** | 云原生监控事实标准，强大的时序数据处理与可视化能力，自定义大屏。 |
| **开发工具** | `Lombok`, `MapStruct`, `Hutool` | 提升开发效率，减少样板代码，规范对象映射。 |

---

## ⚙️ 3. 环境配置 (Environment Configuration)

### 3.1 硬件推荐 (生产环境)
| 组件 | 最低配置 | 推荐配置 | 说明 |
| :--- | :--- | :--- | :--- |
| **应用服务** | 2C4G * 2 | 4C8G * 4 | 根据 QPS 横向扩展，配合 K8s HPA |
| **Redis** | 4G 主从 | 16G 集群版 (3 主 3 从) | 开启 AOF 持久化，确保数据不丢失 |
| **MySQL** | 4C8G SSD | 8C16G NVMe SSD (一主两从) | 开启 Binlog，配置读写分离，优化索引 |
| **RabbitMQ** | 2C4G | 4C8G 镜像队列模式 | 确保消息高可靠，不丢失 |
| **Nacos** | 2C4G * 3 | 4C8G * 3 (集群) | 保证注册中心高可用 |

### 3.2 软件依赖
- **JDK**: 17+ (推荐 Azul Zulu 或 Oracle JDK)
- **Maven**: 3.8+
- **Docker & Docker Compose**: 推荐容器化部署中间件
- **Node.js**: (可选) 用于部分 Grafana 插件

### 3.3 关键配置文件示例

#### 📄 `application.yml` (核心配置片段)
```yaml
spring:
  application:
    name: blog-thumb-service
  cloud:
    nacos:
      discovery:
        server-addr:  $ {NACOS_SERVER:localhost:8848}
      config:
        server-addr:  $ {NACOS_SERVER:localhost:8848}
        file-extension: yaml
        shared-configs:
          - data-id: common-config.yaml
            refresh: true
    sentinel:
      transport:
        dashboard:  $ {SENTINEL_DASHBOARD:localhost:8080}
      datasource:
        ds1:
          nacos:
            server-addr:  $ {NACOS_SERVER}
            dataId:  $ {spring.application.name}-sentinel.json
            ruleType: flow

  # Redisson 配置
  redisson:
    address: "redis:// $ {REDIS_HOST:localhost}:6379"
    password:  $ {REDIS_PASSWORD}
    threads: 16
    netty-threads: 32
    lock-watchdog-timeout: 30000 # 看门狗超时时间 (ms)

  # RabbitMQ 配置
  rabbitmq:
    host:  $ {RABBIT_HOST:localhost}
    port: 5672
    username:  $ {RABBIT_USER:guest}
    password:  $ {RABBIT_PASS:guest}
    listener:
      simple:
        acknowledge-mode: manual # 手动 ACK，确保消息不丢失
        prefetch: 10 # 限流消费，防止消费者过载

  # ShardingSphere 分库分表配置
  shardingsphere:
    datasource:
      names: ds0,ds1
      ds0:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://db0:3306/thumb_db_0?useSSL=false&serverTimezone=UTC
      ds1:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://db1:3306/thumb_db_1?useSSL=false&serverTimezone=UTC
    rules:
      sharding:
        tables:
          thumb:
            actual-data-nodes: ds $ ->{0..1}.thumb_ $ ->{0..3}
            table-strategy:
              standard:
                sharding-column: blog_id
                sharding-algorithm-name: blog-inline
        sharding-algorithms:
          blog-inline:
            type: INLINE
            props:
              algorithm-expression: thumb_ $ ->{blog_id % 4}

# HeavyKeeper 热点检测配置
heavy-keeper:
  enabled: true
  depth: 5           # 二维数组行数 d (哈希函数个数)
  width: 4096        # 每行桶数 w (决定内存大小)
  top-k: 100         # 维护 Top 100 热点
  decay-factor: 0.9  # 衰减因子 (0-1)，越小遗忘越快
  min-count: 10      # 进入 Top-K 的最小频率门槛
