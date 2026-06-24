# Octopus — 多语言代码影响分析平台

> **面向代码提交后的质量决策：基于 commit / tag 提取调用链，定位变更影响范围，辅助确定测试范围，并为需求语义级 LLM Review 和版本演进追踪提供可回溯的源码证据。**
>
> **核心设计：通过 Language Adapter 架构解耦语言分析与核心引擎，每种语言只需实现一个 Adapter 即可接入完整分析链路。**

---

## 1. 项目主要解决的问题

Claude Code、Cursor、Codex 等 Agent 工具已经具备在**编码阶段**提取 Call Graph、检索上下文的能力。Octopus 不重复解决这个问题。

Octopus 关注的是**代码已经提交到仓库之后**的工程质量闭环：

| 问题 | 风险 | Octopus 如何解决 |
|------|------|-----------------|
| **只看到文件 diff** | 不知道修改会影响哪些入口、服务和下游依赖 | 以方法/函数为节点枚举 upstream、downstream 和完整 affected paths |
| **测试范围靠经验判断** | 小改动可能漏测关键链路，大改动又容易过度回归 | 基于调用链影响路径缩小或扩大测试范围，并保留可解释证据 |
| **框架隐式关系不可见** | DI、AOP、事件路由、异步调度等框架机制导致链路断裂 | Adapter 为各语言主流框架注册语义提取器 |
| **LLM Review 缺少业务上下文** | 只审查局部 diff，难判断是否满足需求语义 | 将需求关联到链路后，按路径还原每一步源码供 LLM Review |
| **版本演进难追溯** | 不同 tag/commit 的链路变化只能人工比对 | 按 branch/tag/commit 维度保存图谱快照，支持跨版本路径差异比较 |

**一句话：把代码变更转化为可查询、可解释、可审计的影响范围和源码证据。**

---

## 2. 项目架构

### 架构总览

```
┌──────────────────────────────────────────────────────────────────┐
│  octopus-frontend (React 19, Port 3000)                         │
│  Dashboard · Query · Code Review · Review History · Admin      │
└──────────────────────────┬───────────────────────────────────────┘
                           │ HTTP
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│  code-intelligence (Spring Boot, Port 8080)                     │
│                                                                │
│  ┌─────────────────┐  ┌────────────────┐  ┌───────────────┐   │
│  │ Analysis         │  │ CallGraph      │  │ Query / Diff  │   │
│  │ Orchestration    │──│ Build Service  │──│ / Source      │   │
│  │ Service          │  │                │  │ Services      │   │
│  └────────┬─────────┘  └────────┬───────┘  └───────────────┘   │
│           │                     │                               │
│           ▼                     ▼                               │
│  ┌─────────────────────────────────────────────────────┐       │
│  │  LanguageAdapterRegistry — 按语言分发分析请求         │       │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────┐  │       │
│  │  │ JVM PSI  │ │ JavaParser│ │ Kotlin   │ │ Python│  │ ...   │
│  │  │ Adapter  │ │ Adapter  │ │ Adapter  │ │ (TODO)│  │       │
│  │  └────┬─────┘ └────┬─────┘ └────┬─────┘ └───────┘  │       │
│  └───────┼────────────┼────────────┼───────────────────┘       │
│          │            │            │                            │
│          ▼            ▼            ▼                            │
│  ┌────────────────────────────────────────────────────┐        │
│  │  GitService · GraphStore (Neo4j) · MetadataStore  │        │
│  │  (MySQL) · ReconstructService                     │        │
│  └────────────────────────────────────────────────────┘        │
└──────────────────────────┬──────────────────────────────────────┘
          │                 │                 │
          ▼                 ▼                 ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────────┐
│ octopus-git   │  │   Neo4j      │  │   MySQL          │
│ (Port 8081)  │  │  图数据库    │  │  关系数据库       │
└──────────────┘  └──────────────┘  └──────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  octopus-review-agent (AI Code Review, Port 8083)               │
│  LLM-based code review with Skill engine + Knowledge memory     │
└──────────────────────────────────────────────────────────────────┘
```

![调用图可视化](images/image3.png)

### 模块说明

| 模块 | 职责 | 端口 |
|------|------|------|
| **code-intelligence** | 核心后端 — 分析编排、Adapter 调度、查询 API、调用图构建 | 8080 |
| **jvm-project-analyzer** | JVM Adapter — IntelliJ PSI 插件（Java/Kotlin 深度语义分析） | 8377 |
| **octopus-git** | Git 操作微服务 | 8081 |
| **octopus-code-reconstruct** | 调用路径源码还原服务 | 8082 |
| **octopus-review-agent** | AI Code Review 代理 | 8083 |
| **octopus-infra/\*** | 共享基础设施 | — |
| **octopus-frontend** | React SPA | 3000 |

### Adapter 分发流程

```
AnalysisOrchestrationService
         │
         ▼
LanguageAdapterRegistry.getAdapter(fileExtension)
         │
         ├── .java / .kt ──→ JVM Adapter (PSI 优先, JavaParser 降级)
         ├── .py           ──→ Python Adapter (Sidecar 进程, LSP 驱动)
         ├── .ts / .js     ──→ TypeScript Adapter (Sidecar, ts-morph)
         └── ...           ──→ 未注册 → 跳过/基础文本扫描
```

每种语言的分析结果以标准格式返回，由 `CallGraphBuildService` 统一合并后写入 Neo4j。

---

## 3. JVM Adapter 的设计 —— 为什么选择 PSI + JavaParser

当前最成熟的 Adapter 实现是 **JVM Adapter**，由 IntelliJ PSI（深度）和 JavaParser（轻量）双引擎构成。

### IntelliJ PSI（Program Structure Interface）

**PSI 是 IntelliJ IDEA 的底层解析引擎，作为 JVM Adapter 的"深度"分析器。**

**优势：**
| 能力 | 说明 |
|------|------|
| **语义级解析** | 不仅是 AST，而是完整的符号解析（类型、方法绑定、重载、泛型推导） |
| **框架感知** | 识别 `@Autowired`、`@EventListener`、`@Async`、`@Scheduled`、AOP 切面等框架语义 |
| **跨文件/跨模块引用** | `resolveMethod()` 可以定位方法实际定义位置，正确解析接口多态 |
| **混合语言** | 内置 Kotlin 支持，可分析 Java + Kotlin 混合项目 |

**劣势：**
| 问题 | 影响 |
|------|------|
| 启动慢（30-120s），内存高（2-4 GB） | 不适合批量快速解析 |
| 部署复杂 | 需要下载 ~1.1 GB IDEA，容器中需 Xvfb 模拟显示环境 |
| 单项目隔离 | 每个项目需要独立打开、索引，不能复用状态 |


### JavaParser（轻量级降级路径）

纯 Java 库，毫秒级启动，无类型系统、无框架语义。当 PSI 不可用或不需要深度语义时，automatic fallback。

**双引擎协作模式：**
1. **PSI 优先** — 先尝试 PSI 获取深度分析结果（调用边 + Spring 语义）
2. **自动降级** — PSI 不可用时无缝降级到 JavaParser（文本级源码提取）
3. **JavaParser 补充** — 即使 PSI 成功，JavaParser 仍提供源码文本和精确定位信息
4. **合并写入** — 两路结果由 `CallGraphBuildService` 合并后写入 Neo4j + MySQL

---

## 4. 交互流程

### 4.1 触发分析

对目标仓库的 branch/tag/commit 触发分析任务。系统同步代码、按文件扩展名分发到对应 Language Adapter、将方法节点、调用边、源码位置和版本信息写入存储。

### 4.2 查询影响范围

![扫描 API 端点](images/image1.png)

围绕被修改方法查询调用链，得到：
- **upstream** — 哪些入口、服务或任务会调用到该方法
- **downstream** — 该方法继续影响哪些下游依赖
- **affected paths** — 从上游到下游拼接后的完整链路

用于确定哪些测试入口需要回归：API 端点、消息监听器、定时任务、服务根方法等。

### 4.3 源码还原 + LLM Review

![代码审查界面](images/image2.png)

当链路关联到需求时，按路径还原每一步方法源码，将 Review 上下文从"整个仓库"收敛为"需求相关调用链 + 原始源码 + 版本差异"。LLM 可据此判断：
- 需求规则是否在正确层级实现
- 参数校验、权限、事务、异常处理是否沿链路一致
- 变更是否破坏旧版本行为或遗漏下游副作用

### 4.4 版本对比

跨 commit/tag 对比同一端点的下游路径集合，识别新增、删除和未变化的链路。

---

## 5. 部署与启动

### 前置条件

- Java 21+
- Docker & Docker Compose
- Node.js 20+
- Git

### 启动基础服务（MySQL + Neo4j）

```bash
docker run -d --name mysql-octopus \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=octopus \
  -p 3306:3306 mysql:8.0

docker run -d --name neo4j-octopus \
  -e NEO4J_AUTH=neo4j/octopus123 \
  -p 7474:7474 -p 7687:7687 neo4j:5
```

### 启动基础设施微服务和前端

```bash
docker compose up -d git frontend
```

### 启动 PSI 分析服务（JVM Adapter）

```bash
# macOS（首次运行会下载 IntelliJ IDEA IU 2025.3，约 1.1 GB）
./run-jvm-analyzer-local.sh
```

PSI 服务监听 `localhost:8377`。

### 启动 app

```bash
# 终端 1：后端
./gradlew :code-intelligence:bootRun

# 终端 2：前端开发模式
cd octopus-frontend && npm run dev
```

### 触发分析

```bash
# 分析本地目录
curl -X POST http://localhost:8080/api/analysis/local \
  -H "Content-Type: application/json" \
  -d '{"projectPath": "/path/to/your/project"}'

# 分析 Git 仓库标签
curl -X POST "http://localhost:8080/api/analysis/1?tag=v1.0.0"
```

### Docker 部署

混合部署（Git 服务和前端在 Docker，后端和 PSI 在宿主机）：

```bash
docker compose up -d
```

全容器模式：

```bash
docker compose -f docker-compose.yml -f docker-compose.jvm-analyzer.yml up -d
```

### 关键环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `MYSQL_PASSWORD` | `root` | MySQL 密码 |
| `NEO4J_PASSWORD` | `octopus123` | Neo4j 密码 |
| `GIT_PASSWORD` | — | Git 服务器认证密码 |
| `OCTOPUS_ENCRYPTION_KEY` | — | AES 加密密钥（Git 凭据加密） |

---

## 6. 演进方向

- **非 JVM Adapter 实现**：优先支持 TypeScript/JavaScript（NestJS/Express 路由语义）和 Python（Django/FastAPI 路由语义）
- **Adapter Sidecar 协议标准化**：为跨语言 Adapter 定义 HTTP/gRPC 通信协议，每种语言的分析器以独立进程/容器运行
- **增量分析稳定性**：优化 PSI 索引增量更新可靠性，降低大仓库重复全量分析成本
- **策略模式运行时链路还原**：对 `Map<String, Strategy>`、策略注册表、配置驱动路由等模式，消除静态候选边中的不可能路径
- **Review Agent 可观测性**：审查 Session 的耗时分布、Token 消耗、Skill 执行链路追踪
- **CI/CD 原生集成**：标准化的 GitHub Actions / GitLab CI 插件，提交即分析即反馈

---

## 7. Language Adapter 扩展指南

Octopus 的 Adapter 是**头等抽象**：核心引擎不绑定任何语言，每种语言通过实现统一接口接入。

### Adapter 接口

```java
public interface LanguageAdapter {
    /** 该 Adapter 支持的文件扩展名列表 */
    boolean supports(String fileExtension);
    /** 扫描项目结构，返回模块列表 */
    List<ProjectModule> scanProject(Path projectRoot);
    /** 执行分析，返回节点、边、源码证据 */
    AnalysisResult analyze(Path projectRoot, AnalysisContext ctx);
    /** 是否需要外部引擎进程（如 PSI、LSP Server） */
    boolean requiresExternalEngine();
}
```

### 当前 Adapter 实现状态

| Adapter | 引擎 | 状态 | 能力 |
|---------|------|------|------|
| **JVM PSI** | IntelliJ PSI | ✅ 生产 | 完整语义、Spring 框架理解、15 种调用边类型、混合语言 |
| **JavaParser** | JavaParser | ✅ 生产 | 轻量 AST 解析、自动降级路径、源码文本提取 |
| **Kotlin** | IntelliJ PSI | ✅ 内置 | 通过 Kotlin 插件原生支持 |
| **TypeScript** | ts-morph | ⏳ 计划 | Sidecar 进程，AST + 类型信息 |
| **Python** | Jedi / LSP | ⏳ 计划 | LSP 协议对接 |

### 接入新语言的四个步骤

**第一步：决定运行模式**

| 模式 | 适用场景 | 示例 |
|------|----------|------|
| **内嵌库** | 有 JVM 库可直接调用的语言 | JavaParser（纯 Java） |
| **Sidecar 进程** | 需要语言原生工具链 | TypeScript（ts-morph via Node.js）、Python（Jedi via Python 进程） |
| **外部 HTTP 服务** | 已有现成分析服务 | IntelliJ PSI（独立 HTTP 服务） |

**第二步：实现 `LanguageAdapter`**

- `supports(fileExtension)` — 告诉引擎该处理哪种文件
- `scanProject(projectRoot)` — 发现模块结构（Maven/Gradle/pyproject.toml/package.json）
- `analyze(projectRoot, ctx)` — 核心逻辑返回 `AnalysisResult`（方法节点 + 调用边 + 源码片段）

**第三步：注册框架语义提取器**

每种语言的主流框架都有隐式调用关系（路由注册、依赖注入、事件总线、AOP）。参考 `SpringModelExtractor` 的模式，为框架编写语义提取器：

| 框架 | 隐式关系 | 对应调用边类型 |
|------|----------|---------------|
| Spring (Java) | `@Autowired`, `@EventListener`, AOP, `@Scheduled` | SPRING_DI, EVENT, AOP_*, SCHEDULED |
| NestJS (TS) | `@Controller`, `@Injectable`, `@EventPattern` | SPRING_DI, EVENT |
| Django (Python) | `urlpatterns`, signal, middleware | ROUTE, EVENT |
| FastAPI (Python) | route decorator, Depends, BackgroundTasks | ROUTE, SPRING_DI |

**第四步：注册到 `LanguageAdapterRegistry`**

```java
@Component
public class TypeScriptAdapter implements LanguageAdapter {
    @Override
    public boolean supports(String ext) {
        return Set.of(".ts", ".tsx", ".js", ".jsx").contains(ext);
    }

    @Override
    public AnalysisResult analyze(Path projectRoot, AnalysisContext ctx) {
        // 调用 Node.js Sidecar 进程，传入文件列表
        // 接收标准 JSON 格式的结果
        // 返回 AnalysisResult
    }
}
```

然后在配置或自动扫描中注册：

```yaml
octopus:
  adapters:
    typescript:
      enabled: true
      sidecar-command: node /opt/octopus-ts-adapter/index.js
      sidecar-port: 9100
```

### 参考：TypeScript Adapter 实现思路

```
code-intelligence                              Node.js Sidecar
┌──────────────────────┐     HTTP/stdio        ┌──────────────────────┐
│  TypeScriptAdapter   │ ──────────────────→   │  ts-morph /          │
│  (Java, Language     │                       │  @typescript-estree  │
│   Adapter impl)      │ ←──────────────────   │                      │
│                      │   JSON result          │  Route extractor    │
└──────────────────────┘                       │  (NestJS/Express)   │
                                               └──────────────────────┘
```

Sidecar 输出的标准格式：

```json
{
  "nodes": [
    { "id": "123", "file": "src/controller/UserController.ts",
      "className": "UserController", "methodName": "getUser",
      "parameters": ["id: string"], "returnType": "Promise<User>" }
  ],
  "edges": [
    { "source": "123", "target": "456",
      "type": "DIRECT", "sourceLine": 25,
      "sourceSnippet": "return this.userService.findById(id)" }
  ],
  "frameworkEdges": [
    { "route": "GET /api/users/:id", "handler": "123",
      "type": "ROUTE" }
  ],
  "sourceFiles": {
    "src/controller/UserController.ts": "full source text..."
  }
}
```

---

## 技术栈

| 层次 | 技术 |
|------|------|
| **运行环境** | Java 21, Spring Boot 4.0.5 |
| **构建工具** | Gradle 9.4.1（多模块） |
| **关系数据库** | MySQL 8.0 |
| **图数据库** | Neo4j 5.x |
| **JVM 深度解析器** | IntelliJ Platform IU 2025.3（PSI） |
| **JVM 轻量解析器** | JavaParser 3.26.4 |
| **Git 操作** | JGit 7.6.0 |
| **前端** | React 19, TypeScript 6, Vite 8, Ant Design 5 |
| **图可视化** | @xyflow/react 12.x, dagre |
| **容器化** | Docker, Docker Compose |

---

## License

[MIT License](LICENSE)
