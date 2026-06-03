# Octopus — Java/Spring 代码影响分析平台

> **面向代码提交后的质量决策：从 Git 版本差异中还原 Java/Spring 调用链，定位变更影响范围，辅助确定测试范围，并为需求级 LLM Review 和版本演进追踪提供可回溯的源码证据。**

---

## 目录

- [项目定位](#项目定位)
- [为什么提交后需要影响分析](#为什么提交后需要影响分析)
- [代码质量的三个审视维度](#代码质量的三个审视维度)
- [核心特性](#核心特性)
- [架构总览](#架构总览)
- [典型工作流](#典型工作流)
- [当前局限与风险](#当前局限与风险)
- [为什么选择 PSI + JavaParser](#为什么选择-psi--javaparser)
- [技术栈](#技术栈)
- [快速开始（本地开发）](#快速开始本地开发)
- [配置说明](#配置说明)
- [API 概览](#api-概览)
- [调用图边类型](#调用图边类型)
- [项目结构](#项目结构)
- [贡献指南](#贡献指南)

---

## 项目定位

Claude Code、Cursor、Codex 等 Agent 工具已经具备在**编码阶段**提取 Call Graph、检索上下文、压缩 Token 消耗的能力。Octopus 不重复解决这个问题。

Octopus 关注的是代码已经提交到仓库之后的工程质量闭环：

1. **影响分析**：围绕一次提交、一个 tag 或一段 Git diff，分析被修改方法向上游调用者和下游依赖方传播的范围。
2. **测试范围确定**：把变更影响路径映射到 API 入口、业务服务、Repository、事件监听器、定时任务等链路，帮助 QA 和研发判断需要重点回归哪些场景。
3. **需求正确性 Review**：当需求、接口或业务链路与某条调用路径关联时，可以还原路径上每个方法的源码，让 LLM 基于”真实链路 + 原始源码”判断实现是否符合需求正确性，而不是只看孤立 diff。
4. **版本变迁追踪**：按 branch、tag、commit 保存图谱快照，对比不同版本的端点路径、调用关系和源码证据，追踪业务链路如何演化。

因此，Octopus 的核心不是“帮 Agent 编码时省 Token”，而是“在提交后把代码变更转化为可查询、可解释、可审计的影响范围和源码证据”。

---

## 为什么提交后需要影响分析

代码评审和测试选择通常不缺少 diff，而是缺少 diff 背后的运行链路语义：

| 提交后问题 | 常见风险 | Octopus 如何解决 |
|------|------|-----------------|
| **只看到文件 diff** | 不知道修改会影响哪些入口、服务和下游依赖 | 以方法为节点枚举 upstream、downstream 和完整 affected paths |
| **测试范围靠经验判断** | 小改动可能漏测关键链路，大改动又容易过度回归 | 基于调用链影响路径缩小或扩大测试范围，并保留可解释证据 |
| **Spring 隐式关系不可见** | DI、AOP、事件、异步、定时任务导致链路断裂 | PSI 引擎解析 Spring Bean、注入、AOP、事件发布/订阅等框架语义 |
| **LLM Review 缺少业务上下文** | 只审查局部 diff，难判断是否满足需求正确性 | 将需求关联到链路后，按路径还原每一步方法源码供 LLM Review |
| **版本演进难追溯** | 不同 tag/commit 的链路变化只能人工比对 | 保存 branch/tag/commit 维度图谱快照，支持端点路径差异比较 |

**Octopus 将 Git 版本、调用图、源码片段和查询 API 组合起来，为提交后的测试决策、需求验收和演进追踪提供结构化依据。**

---

## 代码质量的三个审视维度

Octopus 将代码质量检查划分为三个审视维度，以区分编译原理中的”语义分析”（类型检查、名字绑定等）：

| 维度 | 关注点 | 主要检查方式 | Octopus 的作用 |
|------|------|------|------|
| **静态合规性** | 代码是否符合语言的语法规则、类型约束和框架约定的静态规范 | 编译、类型检查、IDE 检查、Lint、单元级规则扫描 | 通过 PSI/JavaParser 提取结构化代码事实，但不替代编译器或静态检查工具 |
| **需求正确性** | 代码实现是否符合需求、业务规则、验收条件和预期链路 | 测试活动、人工 Review、需求验收、LLM Review | 通过 Call Graph 将需求关联到具体调用链和源码片段，辅助判断部分需求正确性 |
| **运行时行为** | 代码在真实流量、数据、配置和环境下的实际表现 | 持续观测、监控、日志、指标、Tracing、线上度量 | 当前不替代运行时观测，但可为链路观测点和风险路径识别提供静态依据 |

第一维度通常由编译器、类型系统和静态规则扫描覆盖；第二维度是测试和 Review 活动主要验证的对象；第三维度依赖持续观测和线上度量。

Octopus 重点切入的是**需求正确性验证的一部分**：当需求可以关联到某个 API、方法或业务链路时，Call Graph 可以把这条需求映射到具体调用路径，再还原路径上的源码。这样做的价值不是让 LLM “读完整个仓库”，而是：

1. **关联具体需求**：让 Review 以需求、验收条件或业务链路为中心，而不是只围绕文件 diff。
2. **减少上下文**：只提供与需求相关的调用路径、方法源码和版本差异，降低无关代码干扰。
3. **聚焦 AI 注意力**：让 LLM 重点检查链路上的规则实现、状态流转、异常处理和下游副作用。

---

## 核心特性

- 🎯 **提交后影响分析** — 从变更方法出发查询上游调用者、下游被调用者和完整受影响路径
- 🧪 **测试范围辅助决策** — 将变更传播路径关联到 API 端点、服务、Repository、事件、定时任务等测试入口
- 🧠 **需求正确性级 LLM Review 证据** — 将需求关联到调用路径并还原每一步方法源码，让 LLM 基于聚焦上下文审查需求实现
- 🕰️ **版本化图谱快照** — 按 branch、tag、commit 维度保存分析结果，支持跨版本路径差异比较
- 🎯 **双引擎静态分析** — IntelliJ PSI（深度）+ JavaParser（轻量）双通道解析
- 🔗 **15 种调用边类型** — 覆盖 DIRECT、SPRING_DI、AOP、EVENT、SCHEDULED、ASYNC、REPOSITORY 等
- 🏗️ **Spring 框架深度理解** — Bean 定义、自动注入、AOP 切面、事件总线、定时任务
- 📦 **多模块项目支持** — 自动检测 Maven/Gradle 子模块，构建跨模块调用图
- 🚀 **增量分析** — 基于 Git tag diff 只分析变化的文件，复用历史版本结果
- 📊 **Neo4j + MySQL 双存储** — 调用图存图数据库，元数据、版本和任务信息存关系数据库
- 🛡️ **优雅降级** — PSI 不可用时自动回退 JavaParser，不中断分析
- 🔍 **REST API + React 前端** — 完整的前后端分离架构，开箱即用

---

## 架构总览

```
┌─────────────────────────┐     ┌──────────────────────────┐
│   octopus-frontend       │     │   octopus-psi             │
│   React 19 + AntD 5     │     │   IntelliJ PSI (IC 2024.3)│
│   (Port 3000)           │     │   (Port 8377)             │
└────────┬────────────────┘     └────────────┬─────────────┘
         │ HTTP                              │ HTTP
         ▼                                   ▼
┌──────────────────────────────────────────────────────────────┐
│                    octopus-server                              │
│     Spring Boot 4.0.5 / Java 21 (Port 8080)                  │
│                                                                │
│  ┌──────────────┐  ┌──────────────┐  ┌─────────────────┐     │
│  │ Analysis      │  │ CodeParse    │  │ CallGraph        │     │
│  │ Orchestration │──│ Service      │──│ Build Service    │     │
│  │ Service       │  │ (JavaParser) │  │                  │     │
│  └──────┬───────┘  └──────────────┘  └────┬───────────┘     │
│         │                                  │                  │
│         ▼                                  ▼                  │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────┐     │
│  │ GitService    │  │ GraphStore   │  │ Metadata        │     │
│  │ Client        │  │ Service      │  │ Store Service   │     │
│  │               │  │ (Neo4j)      │  │ (MySQL)         │     │
│  └──────┬───────┘  └──────┬───────┘  └──────┬─────────┘     │
└─────────┼─────────────────┼─────────────────┼───────────────┘
          │                 │                 │
          │                 │                 │
          ▼                 ▼                 ▼
┌──────────────┐  ┌──────────────────┐  ┌──────────────┐
│ octopus-git   │  │ octopus-code-    │  │  Shared Infra │
│ JGit 微服务  │  │ reconstruct      │  │  (octopus-   │
│ (Port 8081)  │  │ 源码还原服务     │  │   infra）     │
└──────────────┘  │ (Port 8082)      │  │  inf-common   │
                  └──────────────────┘  │  inf-neo4j    │
                                        │  inf-mysql    │
┌──────────────┐  ┌──────────────┐      │  inf-jgit     │
│   Neo4j      │  │   MySQL      │      └──────────────┘
│   图数据库   │  │  关系数据库  │
│   (7687)     │  │   (3306)     │
└──────────────┘  └──────────────┘
```

### 分析流水线

一次完整的分析任务包含以下步骤：

```
SYNC → SCAN → PARSE_DEEP → BUILD → STORE_GRAPH → STORE_METADATA
 │       │        │           │          │             │
 │       │        │           │          ▼             │
 │       │        │           │    写入调用图到        │
 │       │        │           │    Neo4j               │
 │       │        │           │                        │
 │       │        │           ▼                        │
 │       │        │    合并 PSI +                      │
 │       │        │    JavaParser 结果                  │
 │       │        │                                     │
 │       │        ▼                                     ▼
 │       │   PSI 深度分析 +                      写入类/方法信息
 │       │   JavaParser 解析                    到 MySQL
 │       │
 │       ▼
 │  扫描项目结构
 │  (Maven/Gradle)
 │
 ▼
 同步代码
 (Git clone/checkout)
```

---

## 典型工作流

### 1. 提交或发布版本后触发分析

对目标仓库的 branch/tag/commit 触发分析任务。系统同步代码、扫描 Java 项目、解析源码和 Spring 框架构造，并将方法节点、调用边、源码位置、源码片段和版本信息写入存储。

### 2. 从变更点计算影响范围

围绕被修改方法调用 `/api/query/impact-paths`，得到：

- `upstreamPaths`：哪些入口、服务或任务会调用到该方法
- `downstreamPaths`：该方法继续影响哪些下游依赖
- `affectedPaths`：从上游到下游拼接后的完整受影响链路

这些路径可用于判断测试范围：例如哪些 Controller API、消息监听器、定时任务、核心服务或 Repository 需要回归。

### 3. 还原链路源码用于需求正确性 Review

当某条链路关联到需求或缺陷修复时，调用 `/api/query/paths/source` 按路径还原每一步方法源码。这样可以把 Review 上下文从”整个仓库或一组离散 diff”收敛为”需求相关调用链 + 原始源码 + 版本差异”，让 LLM 的注意力集中在需求正确性上。LLM Review 可以据此判断：

- 需求规则是否在正确层级实现
- 参数校验、状态变更、权限、事务、异常处理是否沿链路一致
- 变更是否破坏旧版本行为或遗漏下游副作用

### 4. 对比版本变迁

通过 `/api/query/endpoint-diff-paths` 对比同一 API 端点在两个 commit/tag 下的下游路径集合，识别新增、删除和未变化的链路，用于发布风险评估、回归重点确认和历史演进追踪。

---

## 当前局限与风险

Octopus 当前已经能还原一类 Spring 动态能力场景：例如 `ServiceImpl implements Service` 后，调用点面向接口编程，但实际链路需要落到具体实现类的方法上。这类接口到实现类的真实调用还原是影响分析和源码路径 Review 的基础能力。

但项目仍存在两个重要边界：

| 问题 | 当前状态 | 影响 |
|------|------|------|
| **PSI 性能成本高** | PSI 依赖 IntelliJ 平台，需要项目打开、索引和符号解析，启动慢、内存占用高 | 大仓库或频繁增量分析时，需要通过任务调度、缓存、按模块分析、JavaParser 降级等方式控制成本 |
| **策略模式真实调用还原不足** | 已支持接口到实现类的常见动态能力还原，但对运行时按条件选择策略实现的场景尚不能稳定判断真实目标 | 对 `Map<String, Strategy>`、策略注册表、工厂选择、配置驱动路由等模式，调用图可能只能保留候选边，无法唯一还原实际业务链路 |

因此，Octopus 的分析结果应同时关注路径、边类型、来源和置信度：对接口到实现类的调用链路可以提供更强还原能力；对策略模式等运行时分派场景，则需要在测试范围和 LLM Review 中保守处理，避免把静态候选路径误判为唯一真实路径。

---

## 为什么选择 PSI + JavaParser

### IntelliJ PSI（Program Structure Interface）

**PSI 是 IntelliJ IDEA 的底层解析引擎，也是本项目的"深度"分析器。**

| 优势 | 说明 |
|------|------|
| **语义级解析** | 不仅仅是 AST，而是完整的符号解析（类型、方法绑定、重载解析） |
| **Spring 框架感知** | 能识别 `@Autowired`、`@EventListener`、`@Async`、`@Scheduled`、AOP 切面等框架语义 |
| **跨文件/跨模块引用解析** | 调用 `resolveMethod()` 可以定位到被调用方法的实际定义位置 |
| **类型系统完善** | 支持泛型、继承、接口实现的多态调用解析 |
| **Kotlin 支持** | 内置 Kotlin PSI 解析器，可以分析混合语言项目 |

**代价**：PSI 需要完整的 IntelliJ 平台环境，启动慢（30-120s），内存占用高（2-4 GB），部署复杂。

### JavaParser（轻量级 AST 解析器）

**JavaParser 是本项目的"轻量"分析器，也是自动降级路径。**

| 优势 | 说明 |
|------|------|
| **零依赖** | 纯 Java 库，无外部运行时依赖 |
| **启动极快** | 毫秒级启动，适合大规模批量解析 |
| **部署简单** | 不需要 IntelliJ 平台环境 |
| **增量解析友好** | 可以单文件解析，配合 Git diff 实现精确增量更新 |

**局限**：没有类型系统，无法解析跨模块调用，无法识别 Spring 框架语义。

### 双引擎协作模式

```
         ┌─────────────────────────────────────┐
         │          Octopus 分析引擎              │
         │                                       │
         │  ┌─────────────┐  ┌───────────────┐   │
         │  │  IntelliJ   │  │   JavaParser  │   │
         │  │    PSI      │  │   (降级/补充)  │   │
         │  └──────┬──────┘  └───────┬───────┘   │
         │         │                  │          │
         │         ▼                  ▼          │
         │  ┌──────────────────────────────┐     │
         │  │     CallGraphBuildService     │     │
         │  │  • PSI 提供边 + Spring 语义    │     │
         │  │  • JavaParser 补充源码元数据   │     │
         │  │  • 合并后写入 Neo4j           │     │
         │  └──────────────────────────────┘     │
         └─────────────────────────────────────┘
```

**设计原则**：
1. **PSI 优先**：先尝试 PSI 获取深度分析结果
2. **自动降级**：PSI 不可用时无缝降级到 JavaParser
3. **JavaParser 补充**：即使 PSI 成功，JavaParser 仍提供源代码文本和精确定位
4. **增量回退**：在增量模式下，PSI 分析整个模块，JavaParser 只分析变更的文件

---

## 技术栈

| 层次 | 技术 |
|------|------|
| **运行环境** | Java 21, Spring Boot 4.0.5 |
| **构建工具** | Gradle 9.4.1 (多模块) |
| **关系数据库** | MySQL 8.0 (JPA/Hibernate, `ddl-auto: update`) |
| **图数据库** | Neo4j 5.x (Bolt 驱动) |
| **深度解析器** | IntelliJ Platform IC 2024.3 (PSI) |
| **轻量解析器** | JavaParser 3.26.4 (Symbol Solver) |
| **Git 操作** | JGit 7.6.0 |
| **前端** | React 19.2.6, TypeScript 6.0.2, Vite 8.0.12, Ant Design 5.22.0 |
| **图可视化** | @xyflow/react (React Flow) 12.10.2, @dagrejs/dagre 3.0.0 |
| **容器化** | Docker, Docker Compose |
| **测试** | JUnit Jupiter, jqwik, Testcontainers |

---

## 快速开始（本地开发）

### 前置条件

- Java 21+
- Docker（MySQL + Neo4j）
- Node.js 20+（前端开发）
- Git

### 1. 启动基础服务（MySQL + Neo4j）

```bash
# 启动数据库容器
docker run -d --name mysql-octopus \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=octopus \
  -p 3306:3306 \
  mysql:8.0

docker run -d --name neo4j-octopus \
  -e NEO4J_AUTH=neo4j/octopus123 \
  -p 7474:7474 -p 7687:7687 \
  neo4j:5
```

### 2. 启动 Git 微服务

```bash
./gradlew :octopus-git:bootRun
```

Git 微服务监听 `localhost:8081`，提供仓库 clone、fetch、checkout、diff 等端点。

### 3. 启动 PSI 分析服务

```bash
# macOS (首次运行会下载 IntelliJ IDEA IC 2024.3，约 750 MB)
./run-psi-local.sh

# 或直接在前台运行
./run-psi-local.sh -f
```

PSI 服务监听 `localhost:8377`，提供 `POST /analyze` 和 `GET /health` 端点。

### 4. 启动主后端服务

```bash
./gradlew :octopus-server:bootRun
```

### 5. 启动前端（可选）

```bash
cd octopus-frontend
npm install
npm run dev
```

前端开发服务器监听 `localhost:3000`。

### 6. 一键快速启动

项目提供了 `start-and-analyze.sh` 快捷脚本，可自动完成后端启动和平台分析触发：

```bash
# 确保 MySQL 和 Neo4j 已运行，然后执行
./start-and-analyze.sh
```

### 7. 触发分析

```bash
# 分析本地目录（无需 Git 仓库）
curl -X POST http://localhost:8080/api/analysis/local \
  -H "Content-Type: application/json" \
  -d '{"projectPath": "/path/to/your/java/project"}'

# 或分析 Git 仓库标签
curl -X POST "http://localhost:8080/api/analysis/1?tag=v1.0.0"
```

---

## 配置说明

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MYSQL_PASSWORD` | `root` | MySQL 密码 |
| `NEO4J_PASSWORD` | `octopus123` | Neo4j 密码 |
| `GITHUB_TOKEN` | — | Git 服务器认证令牌 |

### 关键配置项 (`application.yaml`)

```yaml
octopus:
  neo4j:
    uri: bolt://localhost:7687
    username: neo4j
    password: ${NEO4J_PASSWORD:octopus123}
  psi:
    service-url: http://localhost:8377     # PSI HTTP 服务地址
    timeout-seconds: 1500                  # 单模块 PSI 分析超时
    connect-retry-attempts: 3             # 连接重试次数
    connect-retry-interval-seconds: 5     # 重试间隔
  git:
    service-url: http://localhost:8081     # Git 微服务地址
    host: localhost:29418                  # Git 服务器地址
```

---

## API 概览

### 分析服务 (`/api/analysis`)

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/analysis/local` | 分析本地目录（无 Git） |
| `POST` | `/api/analysis/{repoConfigId}` | 分析 Git 仓库标签 |
| `GET` | `/api/analysis/tasks` | 列出所有分析任务 |
| `GET` | `/api/analysis/tasks/{taskId}` | 查询任务状态 |
| `POST` | `/api/analysis/tasks/{taskId}/retry` | 重试失败任务 |
| `GET` | `/api/analysis/{repoConfigId}/tags` | 列出仓库标签 |

### 查询服务 (`/api/query`)

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/query/endpoints` | 查询 HTTP API 端点列表 |
| `GET` | `/api/query/callgraph` | 查询方法调用图（Neo4j） |
| `GET` | `/api/query/projects` | 已分析的项目列表 |
| `GET` | `/api/query/classes` | 类信息查询 |
| `GET` | `/api/query/annotations` | 注解信息查询 |
| `GET` | `/api/query/impact-paths` | 查询方法上下游影响路径，用于测试范围判断 |
| `GET` | `/api/query/endpoint-diff-paths` | 端点变更调用链分析 |
| `POST` | `/api/query/paths/source` | 按调用路径还原每一步方法源码 |

### 源码还原服务 (`/api` on port 8082)

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/reconstruct` | 根据调用路径还原每一步方法源码 |

将需求或缺陷相关的调用路径还原为完整的源码上下文，用于 LLM Review 的聚焦输入。

### Git 微服务 (`/api/git` on port 8081)

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/git/clone` | 克隆仓库 |
| `POST` | `/api/git/fetch` | 拉取更新 |
| `POST` | `/api/git/checkout` | 检出标签 |
| `GET` | `/api/git/tags` | 列出标签 |

### PSI 服务 (port 8377)

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/analyze` | 分析项目目录 |
| `GET` | `/health` | 健康检查 |
| `POST` | `/shutdown` | 优雅关闭 |

---

## 调用图边类型

| 类型 | 来源 | 说明 |
|------|------|------|
| `DIRECT` | PSI / JavaParser | 直接方法调用 |
| `INTERFACE` | PSI | 接口方法调用（运行时多态） |
| `SUPER` | PSI | `super.method()` 调用 |
| `LAMBDA` | JavaParser | Lambda 表达式调用 |
| `SPRING_DI` | PSI | Spring 依赖注入 (@Autowired) |
| `AOP_BEFORE` | PSI | AOP 前置通知 |
| `AOP_AROUND` | PSI | AOP 环绕通知 |
| `AOP_AFTER` | PSI | AOP 后置通知 |
| `AOP_AFTER_RETURNING` | PSI | AOP 返回通知 |
| `AOP_AFTER_THROWING` | PSI | AOP 异常通知 |
| `EVENT_PUBLISH` | PSI | 事件发布 → 监听器 |
| `SCHEDULED` | PSI | @Scheduled 定时任务 |
| `ASYNC` | PSI | @Async 异步调用 |
| `REPOSITORY` | PSI | Spring Data JPA 仓库方法 |
| `HTTP_CLIENT` | 预留 | HTTP 客户端调用 |

---

## 项目结构

```
octopus/
├── octopus-server/                    # 主后端服务 (Port 8080)
│   └── src/main/java/io/yuan/octopus/
│       ├── controller/               # REST 控制器
│       ├── service/                  # 核心业务逻辑
│       │   ├── AnalysisOrchestrationService  # 分析流水线编排
│       │   ├── CodeParseService             # JavaParser 解析
│       │   ├── CallGraphBuildService         # 调用图构建
│       │   ├── GraphStoreService             # Neo4j 存储
│       │   ├── MetadataStoreService          # MySQL 存储
│       │   ├── PSIExecutor                   # PSI 客户端
│       │   ├── QueryService                  # 查询引擎
│       │   ├── PathQueryService              # 高级路径查询
│       │   ├── GitServiceClient              # Git 服务客户端
│       │   └── ProjectScanService            # 项目结构扫描
│       ├── domain/                    # 领域模型 (CallGraph, PSIAnalysisOutput 等)
│       ├── entity/                    # JPA 实体
│       ├── repository/                # 数据访问
│       ├── config/                    # Spring 配置
│       ├── dto/                       # 数据传输对象
│       ├── common/                    # 枚举和常量
│       ├── exception/                 # 自定义异常层次
│       └── utils/                     # 工具类
│
├── octopus-psi/                       # IntelliJ PSI 深度分析引擎 (Port 8377)
│   └── src/main/java/io/yuan/octopus/psi/
│       ├── PsiAnalyzerMain           # 入口 (ApplicationStarter)
│       ├── AnalysisHttpServer        # HTTP 服务模式
│       ├── ProjectBootstrap          # 项目打开/索引
│       ├── CallGraphExtractor        # 调用图提取
│       ├── SpringModelExtractor      # Spring Bean 定义提取
│       ├── AOPExtractor              # AOP 切面绑定提取
│       ├── EventExtractor            # 事件发布/监听提取
│       ├── ScheduledExtractor        # @Scheduled 定时任务提取
│       ├── AsyncExtractor            # @Async 异步调用提取
│       └── RepositoryExtractor       # Spring Data 仓库方法提取
│
├── octopus-git/                       # Git 微服务 (Port 8081)
│   └── src/main/java/io/yuan/octopus/git/
│       ├── GitController             # REST API
│       ├── GitRepositoryService      # JGit 操作 (clone/fetch/checkout/diff)
│       └── RepoCleanupService        # 定期清理
│
├── octopus-code-reconstruct/          # 源码还原服务 (Port 8082)
│   └── src/main/java/io/yuan/octopus/reconstruct/
│       ├── ReconstructController     # REST API
│       ├── ReconstructService        # 还原编排
│       ├── CallChainCollector        # Neo4j 调用链收集
│       ├── ClassSourceAssembler      # 类源码拼接
│       └── FieldUsageFilter          # 字段使用过滤
│
├── octopus-infra/                     # 共享基础设施库
│   └── infra-bom/                    # BOM 版本管理
│   └── infra-common/                 # 通用工具 (Result, CredentialEncryptor 等)
│   └── infra-neo4j/                  # Neo4j Bolt 驱动配置
│   └── infra-mysql/                  # MySQL JPA 审计监听器
│   └── infra-jgit/                   # JGit 操作抽象与认证配置
│
├── octopus-frontend/                  # React SPA 前端 (Port 3000)
│   └── src/
│       ├── pages/                    # 页面
│       │   ├── Dashboard             # 仪表盘
│       │   ├── Repos                 # 仓库管理
│       │   ├── Analysis              # 分析任务
│       │   └── Query                 # 数据查询（调用图可视化、类详情、端点）
│       ├── components/               # 通用组件 (Layout, Navbar 等)
│       ├── api/                      # Axios API 客户端
│       ├── types/                    # TypeScript 类型定义
│       └── routes/                   # 路由配置
│
├── start-and-analyze.sh              # 快捷启动脚本
├── run-psi-local.sh                  # 本地 PSI 启动脚本
├── build.gradle                      # 根构建配置
├── settings.gradle                   # 多模块声明
├── .dockerignore                     # Docker 构建忽略规则
└── gradlew                           # Gradle Wrapper
```

---

## 贡献指南

### 开发环境准备

```bash
# 克隆项目
git clone https://github.com/your-org/octopus.git
cd octopus

# 构建全部模块
./gradlew build

# 确认编译通过
./gradlew compileJava
```

### 代码风格

- Java 代码遵循 [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- 使用 `Lombok` 减少样板代码（`@Builder`, `@Getter`, `@Setter`）
- 不可变对象使用 `record` 类型
- Spring Bean 使用构造器注入
- 日志使用 SLF4J `Logger` 接口

---

## License

[MIT License](LICENSE)
