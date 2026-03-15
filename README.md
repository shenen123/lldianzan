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

# ⚙️ 核心配置文件说明 (Configuration Guide)

本项目配置分为两部分，均已进行**脱敏处理**。生产环境部署时，请通过环境变量或配置中心（如 Nacos）注入真实值。

1. **`application.yml`**：Spring Boot 应用主配置，包含服务发现、缓存、消息队列及分库分表逻辑。
2. **`shardingsphere-config-debug.yaml`**：ShardingSphere 独立运行时的调试配置（或作为配置中心的数据源），用于验证分片规则。
---

## 1. 应用主配置 (`application.yml`)

此文件定义了微服务的基础设施连接及核心业务逻辑参数。

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.h2.H2ConsoleAutoConfiguration
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
  application:
    name: lldianzan
  
  # --- Nacos 配置中心与服务发现 ---
  cloud:
    nacos:
      config:
        # [注意] 生产环境请修改为 Nacos 集群地址或内部域名
        server-addr: ${NACOS_SERVER:127.0.0.1:8848}
        file-extension: json
      discovery:
        server-addr: ${NACOS_SERVER:127.0.0.1:8848}
    
    # --- Sentinel 流量防护 ---
    sentinel:
      transport:
        # [注意] 生产环境请修改为 Sentinel 控制台地址
        dashboard: ${SENTINEL_DASHBOARD:localhost:8080}
      datasource:
        flow:
          nacos:
            server-addr: ${NACOS_SERVER:127.0.0.1:8848}
            data-id: ${spring.application.name}-flow-rules
            group-id: DEFAULT_GROUP
            rule-type: flow
            data-type: json
        degrade:
          nacos:
            server-addr: ${NACOS_SERVER:127.0.0.1:8848}
            data-id: ${spring.application.name}-degrade-rules
            group-id: DEFAULT_GROUP
            rule-type: degrade
            data-type: json

  # --- RabbitMQ 消息队列 ---
  rabbitmq:
    # [注意] 生产环境请使用独立账号，不要使用 guest
    host: ${RABBIT_HOST:127.0.0.1}
    port: 5672
    username: ${RABBIT_USER:guest}
    password: ${RABBIT_PASSWORD:change_me_strong_password}
    virtual-host: /
    listener:
      simple:
        acknowledge-mode: auto # 建议生产环境改为 manual 手动 ack
        concurrency: 5
        max-concurrency: 20
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1000ms
          multiplier: 2.0
          max-interval: 10000ms
    template:
      retry:
        enabled: true
        max-attempts: 3
        initial-interval: 1000ms
      mandatory: true

  # --- Elasticsearch 搜索引擎 ---
  elasticsearch:
    uris: http://${ES_HOST:localhost}:9200

  profiles:
    active: debug

  # --- Redis 缓存 ---
  redis:
    host: ${REDIS_HOST:127.0.0.1}
    port: 6379
    database: 3
    timeout: 5000ms
    # [注意] 如果有密码请取消注释并配置
    # password: ${REDIS_PASSWORD:}

  # --- Redisson 分布式锁 ---
  redisson:
    config:
      singleServerConfig:
        address: "redis://${REDIS_HOST:127.0.0.1}:6379"
        database: 3
        timeout: 5000
        connectionMinimumIdleSize: 10
        connectionPoolSize: 64
        # password: ${REDIS_PASSWORD:}

# --- API 文档配置 (Knife4j/Swagger) ---
springdoc:
  swagger-ui:
    path: /swagger-ui.html
    tags-sorter: alpha
    operations-sorter: alpha
  api-docs:
    path: /v3/api-docs
  group-configs:
    - group: 'default'
      paths-to-match: '/**'
      packages-to-scan: com.liubinrui.controller

knife4j:
  enable: true
  setting:
    language: zh_cn

# --- 服务器配置 ---
server:
  address: 0.0.0.0
  port: 8120
  servlet:
    context-path: /api
    session:
      cookie:
        max-age: 2592000 # 30天

# --- MyBatis Plus 配置 ---
mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl # 生产环境建议关闭或改为 warn
  global-config:
    db-config:
      logic-delete-field: isDelete
      logic-delete-value: 1
      logic-not-delete-value: 0
      id-type: ASSIGN_ID  # 雪花算法生成 ID

# --- 业务自定义配置 ---
blog:
  follow:
    active-days: 30
  push:
    small-v: 5
    medium-v: 100000

## 2. ShardingSphere配置 (`shardingsphere-config-debug.yaml`)
# 数据源配置
dataSources:
  ds_0:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    driverClassName: com.mysql.cj.jdbc.Driver
    # [注意] 生产环境请修改 IP 并使用环境变量注入密码
    jdbcUrl: jdbc:mysql://${DB_HOST:localhost}:3306/dianzan?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&characterEncoding=utf-8
    username: ${DB_USER:root}
    password: ${DB_PASSWORD:change_me_strong_password}
    maximumPoolSize: 20
    minimumIdle: 5
    connectionTimeout: 30000
    idleTimeout: 60000
    maxLifetime: 1800000

# 分片规则
rules:
  - !SHARDING
    tables:
      thumb:
        # 逻辑表 thumb 映射到物理表 thumb_0 到 thumb_127
        actualDataNodes: ds_0.thumb_${0..127}
        
        tableStrategy:
          standard:
            shardingColumn: blog_id
            # 使用自定义类进行分片计算
            shardingAlgorithmName: thumb-mod-algo

        keyGenerateStrategy:
          column: id
          keyGeneratorName: snowflake

    shardingAlgorithms:
      thumb-mod-algo:
        type: CLASS_BASED
        props:
          strategy: STANDARD
          # [重要] 确保该类路径在你的项目中存在且正确
          algorithmClassName: com.liubinrui.config.sharding.BlogIdModShardingAlgorithm

    keyGenerators:
      snowflake:
        type: SNOWFLAKE
        props:
          # [注意] 集群部署时，每个节点的 worker-id 必须唯一
          worker-id: 1

  # 单表规则：自动管理未分片的表
  - !SINGLE
    tables:
      - "*.*"
    defaultDataSource: ds_0

# 属性配置
props:
  sql-show: true # [警告] 生产环境务必设置为 false，避免日志打印影响性能
