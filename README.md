<div align="center">

# 🌊 长江河岸崩塌风险评估系统 — 后端服务

**Yangtze River Bank Collapse Risk Assessment System — Backend Service**

基于 Spring Boot 4.0 + MyBatis + PostGIS 的多模块河岸崩塌风险评估后端服务。

[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0-6DB33F?style=flat-square&logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![MyBatis](https://img.shields.io/badge/MyBatis-3.5-EB5E28?style=flat-square)](https://mybatis.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-PostGIS-4169E1?style=flat-square&logo=postgresql&logoColor=white)](https://postgis.net/)
[![Kafka](https://img.shields.io/badge/Kafka-Optional-231F20?style=flat-square&logo=apachekafka&logoColor=white)](https://kafka.apache.org/)

</div>

---

## 📖 目录 / Table of Contents

- [项目简介](#项目简介)
- [核心特性](#核心特性)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [API 概览](#api-概览)
- [部署架构](#部署架构)
- [License](#license)

---

## 项目简介

`bank-warning-server` 是长江河岸崩塌风险评估系统的 Java 后端服务，负责：

- 对外暴露 `/v0/bank/*` 业务 API（任务管理、断面管理、结果查询等）
- 直接操作 PostgreSQL/PostGIS 数据库，管理任务、断面、岸段、风险结果等数据
- 编排任务执行流程：读取断面参数 → 调用 Python 模型服务 → 轮询结果 → 持久化风险等级
- 支持 Kafka 异步模式，实现分布式多节点计算

---

## 核心特性

| 特性 | 说明 |
|------|------|
| **多模块架构** | `core`（共享端口/DTO）+ `kafka`（可选实现）+ `app`（可执行主应用） |
| **MyBatis 持久化** | Mapper + XML 映射，支持逻辑删除、PostGIS 空间查询 |
| **Kafka 可选** | 默认同步执行；启用 Kafka 后支持异步分布式计算 |
| **断面级容错** | 单断面失败不阻塞其他断面，支持 `partial_failed` 状态 |
| **TIFF 管理** | 支持单个/批量上传地形 TIFF，自动提取边界存入 PostGIS |
| **逻辑删除** | 所有表使用 `deleted_at` 时间戳，支持数据恢复 |
| **PostGIS 空间** | 断面几何验证、空间查询、坐标系转换 |

---

## 技术栈

| 层级 | 技术 |
|------|------|
| **语言** | Java 21 |
| **框架** | Spring Boot 4.0.0 |
| **持久化** | MyBatis 3.5 + PostgreSQL 15 + PostGIS |
| **消息队列** | Apache Kafka 4.0（可选） |
| **构建** | Maven 多模块 |
| **序列化** | Jackson (JSON) |
| **存储** | RustFS（S3 兼容） |

---

## 项目结构

```
bank-warning-server/
├── bank-warning-core/          # 共享模块：端口定义、Kafka DTO
│   └── src/main/java/com/yangtze/bankwarning/
│       ├── dto/kafka/          # Kafka 消息 DTO（ModelTask、ModelResult）
│       └── service/async/      # 异步端口定义
│
├── bank-warning-kafka/         # Kafka 实现模块（可选）
│   └── src/main/java/com/yangtze/bankwarning/
│       ├── config/             # Kafka 配置
│       ├── kafka/              # 生产者、消费者
│       └── service/async/      # 端口实现
│
├── bank-warning-app/           # 主应用模块（可执行）
│   └── src/main/java/com/yangtze/bankwarning/
│       ├── config/             # 配置类（MyBatis、CORS 等）
│       ├── controller/         # REST 控制器
│       ├── domain/
│       │   ├── dto/            # 业务 DTO（BankPayload、TaskPayload 等）
│       │   └── po/             # 持久化对象（TaskPO、BankPO 等）
│       ├── mapper/             # MyBatis Mapper 接口
│       ├── service/            # 业务服务层
│       └── kafka/              # Kafka 消费者（结果回传）
│   └── src/main/resources/
│       ├── mapper/             # MyBatis XML 映射文件
│       └── application.yml     # 应用配置
│
└── pom.xml                     # 父 POM（多模块声明）
```

---

## 快速开始

### 前置条件

- **JDK 21+**
- **Maven 3.9+**
- **PostgreSQL 15+**（需启用 PostGIS 扩展）
- **Python 模型服务**（默认 `http://localhost:8088`）
- **Kafka**（可选，默认不启用）

### 1. 克隆项目

```bash
git clone <repository-url>
cd bank-warning-server
```

### 2. 编译

```bash
mvn clean install -DskipTests
```

### 3. 配置数据库

```bash
# 创建数据库
createdb bank_risk_db

# 启用 PostGIS 扩展
psql -d bank_risk_db -c "CREATE EXTENSION IF NOT EXISTS postgis;"
```

### 4. 启动服务

```bash
# 同步模式（默认，无需 Kafka）
mvn -pl bank-warning-app spring-boot:run

# 异步模式（启用 Kafka）
mvn -pl bank-warning-app spring-boot:run \
  -Dspring-boot.run.profiles=kafka \
  -DKAFKA_ENABLED=true
```

服务启动后访问：`http://localhost:8090`

---

## 配置说明

### 环境变量

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `BANK_DB_URL` | `jdbc:postgresql://localhost:5432/bank_risk_db` | 数据库连接地址 |
| `BANK_DB_USERNAME` | `postgres` | 数据库用户名 |
| `BANK_DB_PASSWORD` | `123456` | 数据库密码 |
| `KAFKA_ENABLED` | `false` | 是否启用 Kafka 模式 |
| `KAFKA_BOOTSTRAP_SERVERS` | `127.0.0.1:9092` | Kafka 服务地址 |
| `KAFKA_TASK_TOPIC` | `bank.model.task` | 任务 Topic |
| `KAFKA_RESULT_TOPIC` | `bank.model.result.v1` | 结果 Topic |

### 运行模式

| 模式 | 启动命令 | 说明 |
|------|---------|------|
| **同步** | `mvn -pl bank-warning-app spring-boot:run` | 直接调用 Python 服务，适合开发调试 |
| **异步** | `mvn -pl bank-warning-app spring-boot:run -DKAFKA_ENABLED=true` | 通过 Kafka 分发任务，适合生产环境 |

---

## API 概览

### 任务管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/v0/bank/tasks` | 创建任务 |
| `GET` | `/v0/bank/tasks` | 查询任务列表 |
| `GET` | `/v0/bank/tasks/{task_id}` | 查询任务详情 |
| `DELETE` | `/v0/bank/tasks/{task_id}` | 删除任务（逻辑删除） |
| `POST` | `/v0/bank/tasks/{task_id}/run` | 执行任务（同步） |
| `POST` | `/v0/bank/tasks/{task_id}/run/async` | 执行任务（异步，需 Kafka） |

### 断面管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/v0/bank/sections` | 创建断面 |
| `GET` | `/v0/bank/sections` | 查询断面列表 |
| `DELETE` | `/v0/bank/sections/{section_id}` | 删除断面（逻辑删除） |

### 岸段管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/v0/bank/banks` | 创建岸段 |
| `GET` | `/v0/bank/banks` | 查询岸段列表 |
| `DELETE` | `/v0/bank/banks/{bank_id}` | 删除岸段（逻辑删除） |

### TIFF 管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/v0/bank/tiffs` | 列出所有 TIFF |
| `POST` | `/v0/bank/tiffs/upload` | 上传单个 TIFF |
| `POST` | `/v0/bank/tiffs/batch-upload` | 批量上传 TIFF |
| `DELETE` | `/v0/bank/tiffs?tiff_key=...` | 删除 TIFF |

### 结果查询

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/v0/bank/results` | 查询风险结果列表 |
| `GET` | `/v0/bank/results/{task_id}` | 查询任务结果详情 |

---

## 部署架构

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   前端 (Vue)     │────▶│  Java 后端       │────▶│  Python 模型服务 │
│   Port: 5173    │     │  Port: 8090     │     │  Port: 8088     │
└─────────────────┘     └────────┬────────┘     └─────────────────┘
                                 │
                    ┌────────────┼────────────┐
                    ▼            ▼            ▼
             ┌──────────┐ ┌──────────┐ ┌──────────┐
             │PostgreSQL│ │  RustFS  │ │  Kafka   │
             │ + PostGIS│ │ (S3兼容) │ │  (可选)  │
             └──────────┘ └──────────┘ └──────────┘
```

### 分布式计算（启用 Kafka 时）

- 前端只连接主节点 Java 服务（端口 8090）
- 计算节点通过 Kafka 竞争消费任务
- TIFF 文件通过 RustFS 共享存储
- 结果按 `task_id + section_id` 合并到 PostgreSQL

---

## License

本项目仅供学术研究使用。

---

<div align="center">

**[English Version](#english-version)**

</div>

---

<a id="english-version"></a>

# 🌊 Yangtze River Bank Collapse Risk Assessment System — Backend Service

A multi-module Spring Boot 4.0 + MyBatis + PostGIS backend service for river bank collapse risk assessment.

## Table of Contents

- [Introduction](#introduction)
- [Features](#features)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [API Overview](#api-overview)
- [Deployment Architecture](#deployment-architecture)
- [License](#license-en)

---

## Introduction

`bank-warning-server` is the Java backend service for the Yangtze River Bank Collapse Risk Assessment System. It handles:

- Exposing `/v0/bank/*` business APIs (task management, section management, result queries)
- Operating directly on PostgreSQL/PostGIS database for tasks, sections, river banks, and risk results
- Orchestrating task execution: read section parameters → call Python model service → poll results → persist risk levels
- Supporting Kafka async mode for distributed multi-node computation

---

## Features

| Feature | Description |
|---------|-------------|
| **Multi-module Architecture** | `core` (shared ports/DTOs) + `kafka` (optional implementation) + `app` (executable main application) |
| **MyBatis Persistence** | Mapper + XML mapping with logical deletion and PostGIS spatial queries |
| **Kafka Optional** | Sync execution by default; async distributed computing when Kafka is enabled |
| **Section-level Fault Tolerance** | Single section failure doesn't block others; supports `partial_failed` status |
| **TIFF Management** | Single/batch upload terrain TIFFs with automatic boundary extraction to PostGIS |
| **Logical Deletion** | All tables use `deleted_at` timestamps for soft delete and data recovery |
| **PostGIS Spatial** | Section geometry validation, spatial queries, coordinate system transformation |

---

## Tech Stack

| Layer | Technology |
|-------|------------|
| **Language** | Java 21 |
| **Framework** | Spring Boot 4.0.0 |
| **Persistence** | MyBatis 3.5 + PostgreSQL 15 + PostGIS |
| **Message Queue** | Apache Kafka 4.0 (optional) |
| **Build** | Maven multi-module |
| **Serialization** | Jackson (JSON) |
| **Storage** | RustFS (S3 compatible) |

---

## Project Structure

```
bank-warning-server/
├── bank-warning-core/          # Shared module: ports, Kafka DTOs
│   └── src/main/java/com/yangtze/bankwarning/
│       ├── dto/kafka/          # Kafka message DTOs (ModelTask, ModelResult)
│       └── service/async/      # Async port definitions
│
├── bank-warning-kafka/         # Kafka implementation module (optional)
│   └── src/main/java/com/yangtze/bankwarning/
│       ├── config/             # Kafka configuration
│       ├── kafka/              # Producers, consumers
│       └── service/async/      # Port implementations
│
├── bank-warning-app/           # Main application module (executable)
│   └── src/main/java/com/yangtze/bankwarning/
│       ├── config/             # Configuration classes (MyBatis, CORS, etc.)
│       ├── controller/         # REST controllers
│       ├── domain/
│       │   ├── dto/            # Business DTOs (BankPayload, TaskPayload, etc.)
│       │   └── po/             # Persistent objects (TaskPO, BankPO, etc.)
│       ├── mapper/             # MyBatis Mapper interfaces
│       ├── service/            # Business service layer
│       └── kafka/              # Kafka consumers (result callback)
│   └── src/main/resources/
│       ├── mapper/             # MyBatis XML mapping files
│       └── application.yml     # Application configuration
│
└── pom.xml                     # Parent POM (multi-module declaration)
```

---

## Getting Started

### Prerequisites

- **JDK 21+**
- **Maven 3.9+**
- **PostgreSQL 15+** (with PostGIS extension enabled)
- **Python model service** (default: `http://localhost:8088`)
- **Kafka** (optional, disabled by default)

### 1. Clone the Repository

```bash
git clone <repository-url>
cd bank-warning-server
```

### 2. Build

```bash
mvn clean install -DskipTests
```

### 3. Setup Database

```bash
# Create database
createdb bank_risk_db

# Enable PostGIS extension
psql -d bank_risk_db -c "CREATE EXTENSION IF NOT EXISTS postgis;"
```

### 4. Start the Service

```bash
# Sync mode (default, no Kafka required)
mvn -pl bank-warning-app spring-boot:run

# Async mode (with Kafka)
mvn -pl bank-warning-app spring-boot:run \
  -Dspring-boot.run.profiles=kafka \
  -DKAFKA_ENABLED=true
```

Access the service at: `http://localhost:8090`

---

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `BANK_DB_URL` | `jdbc:postgresql://localhost:5432/bank_risk_db` | Database connection URL |
| `BANK_DB_USERNAME` | `postgres` | Database username |
| `BANK_DB_PASSWORD` | `123456` | Database password |
| `KAFKA_ENABLED` | `false` | Enable Kafka mode |
| `KAFKA_BOOTSTRAP_SERVERS` | `127.0.0.1:9092` | Kafka server address |
| `KAFKA_TASK_TOPIC` | `bank.model.task` | Task topic |
| `KAFKA_RESULT_TOPIC` | `bank.model.result.v1` | Result topic |

### Run Modes

| Mode | Command | Description |
|------|---------|-------------|
| **Sync** | `mvn -pl bank-warning-app spring-boot:run` | Direct call to Python service, suitable for development |
| **Async** | `mvn -pl bank-warning-app spring-boot:run -DKAFKA_ENABLED=true` | Task distribution via Kafka, suitable for production |

---

## API Overview

### Task Management

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/v0/bank/tasks` | Create task |
| `GET` | `/v0/bank/tasks` | List tasks |
| `GET` | `/v0/bank/tasks/{task_id}` | Get task details |
| `DELETE` | `/v0/bank/tasks/{task_id}` | Delete task (logical) |
| `POST` | `/v0/bank/tasks/{task_id}/run` | Execute task (sync) |
| `POST` | `/v0/bank/tasks/{task_id}/run/async` | Execute task (async, requires Kafka) |

### Section Management

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/v0/bank/sections` | Create section |
| `GET` | `/v0/bank/sections` | List sections |
| `DELETE` | `/v0/bank/sections/{section_id}` | Delete section (logical) |

### Bank (River Section) Management

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/v0/bank/banks` | Create bank section |
| `GET` | `/v0/bank/banks` | List bank sections |
| `DELETE` | `/v0/bank/banks/{bank_id}` | Delete bank section (logical) |

### TIFF Management

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v0/bank/tiffs` | List all TIFFs |
| `POST` | `/v0/bank/tiffs/upload` | Upload single TIFF |
| `POST` | `/v0/bank/tiffs/batch-upload` | Batch upload TIFFs |
| `DELETE` | `/v0/bank/tiffs?tiff_key=...` | Delete TIFF |

### Result Queries

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/v0/bank/results` | List risk results |
| `GET` | `/v0/bank/results/{task_id}` | Get task result details |

---

## Deployment Architecture

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Frontend (Vue) │────▶│  Java Backend   │────▶│  Python Model   │
│   Port: 5173    │     │  Port: 8090     │     │  Port: 8088     │
└─────────────────┘     └────────┬────────┘     └─────────────────┘
                                 │
                    ┌────────────┼────────────┐
                    ▼            ▼            ▼
             ┌──────────┐ ┌──────────┐ ┌──────────┐
             │PostgreSQL│ │  RustFS  │ │  Kafka   │
             │ + PostGIS│ │(S3 compat)│ │(optional)│
             └──────────┘ └──────────┘ └──────────┘
```

### Distributed Computing (with Kafka enabled)

- Frontend only connects to the main node Java service (port 8090)
- Compute nodes compete for tasks via Kafka consumer groups
- TIFF files are shared via RustFS storage
- Results are merged into PostgreSQL by `task_id + section_id`

---

<a id="license-en"></a>

## License

This project is for academic research purposes only.
