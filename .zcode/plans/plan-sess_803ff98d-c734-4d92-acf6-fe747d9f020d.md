# Go 项目 Graph 构建模块（基于 Adapter 模式）

## 设计决策（按推荐选项定稿）

1. **解析器形态：Go sidecar 服务** — 新建 `go-project-analyzer/` 模块（Go 语言编写），用 `golang.org/x/tools/go/packages` + `go/types` 官方类型系统精确解析，常驻 HTTP 服务（端口 8378），完全镜像 jvm-project-analyzer + PSIExecutor 架构。Java 侧零解析逻辑。
2. **接口分派：Go 侧扇出** — `types.Implements` 找到工作区内所有实现类型，直接生成调用方→具体实现的 DIRECT 边；找不到实现时保留一条指向接口方法节点的 INTERFACE 边。
3. **入口识别：MVP 仅 Root Method** — 无调用者的方法（main/init/handler）自动成为入口，现有查询零改动支持。HTTP 路由识别（gin/echo/net-http 注册）留作后续迭代。

## 数据契约（单一权威：Go 侧生成，Java 侧只消费）

**签名格式**：`<qualifiedClassName>.<name>(<paramTypes逗号连接>):<returnTypes逗号连接>`
- 自由函数：qualifiedClassName = 包路径（`github.com/x/svc/user`）
- 方法：`github.com/x/svc/user.Service.GetUser(context.Context,string):*User,error`
- 类型/参数用 go/types 规范字符串（`context.Context`、`*User`、`error`、`[]byte`）

**`POST /analyze` 响应**（镜像 PSI 服务契约 `{"success":true,"data":{...}}`）：
```json
{"types":   [{"qualifiedName":"pkg.Service","name":"Service","kind":"struct|interface",
              "packagePath":"...","file":"...","lineStart":N,"lineEnd":N,
              "embedded":"pkg.Base|null","implements":["pkg.Iface"],"fields":[{"name","type"}]}],
 "methods": [{"qualifiedClassName":"pkg.Service","methodName":"GetUser",
              "signature":"pkg.Service.GetUser(...):...","parameterTypes":[...],
              "returnTypes":[...],"modifiers":"","sourceFilePath":"...","sourceCode":"...",
              "lineStart":N,"lineEnd":N}],
 "edges":   [{"callerSignature":"...","calleeSignature":"...",
              "callType":"DIRECT|INTERFACE","lineNumber":N}]}
```
Go 侧只输出扫描模块内的方法与边（stdlib/三方依赖边不输出，省去 Java 侧过滤）。排除 `*_test.go`、`vendor/`、`testdata/`。

## Phase 1 — SPI 提升 + 语言路由（JVM 行为不变）

1. **`analyzer/ProjectAnalyzer.java`**：把 JvmProjectAnalyzer 已有的三个管线方法提升为接口方法（签名完全一致，JVM 实现零改动）：
   - `List<DiscoveredProject> scanProjects(RepoSnapshot snapshot)`
   - `DiscoveredProject scanSingleProject(Path projectRoot, String projectName)`
   - `List<String> analyzeDeep(AnalysisContext ctx, List<DiscoveredProject> projects, RepoSnapshot snapshot)`（返回降级模块名列表）
2. **`AnalysisOrchestrationService`**：两处 `analysisDispatcher.getJvmAnalyzer()`（`runLocalPipeline`:175、`runChangeAnalysisPipeline`:387）改为：
   ```java
   ProjectAnalyzer analyzer = analysisDispatcher.detectAnalyzer(resolvedPath)
           .orElseGet(analysisDispatcher::getJvmAnalyzer);  // 无构建文件目录保持 JVM 兜底（现行为）
   ```
3. 回归：跑 code-intelligence 现有测试确认 JVM 链路不受影响。

## Phase 2 — go-project-analyzer 服务（Go 模块，不进 Gradle 构建）

目录：`go-project-analyzer/`（`go.mod`、`main.go`、`analyzer.go`、`httpserver.go`、`analyzer_test.go` + `testdata/`）

1. **main.go**：`serve --port 8378`（默认），`go build -o bin/go-analyzer` 出二进制。
2. **httpserver.go**：`/health`（`{"status":"ok","uptime":N}`）、`/analyze`（请求 `{"projectDir":"..."}`；单飞互斥锁——与 PSI 一致；400/500/503 错误语义同 PSI 服务）、`/shutdown`。
3. **analyzer.go** 核心：
   - 找模块：有界深度遍历 `go.mod`（跳过 vendor/testdata/.git），每个 go.mod = 一个模块
   - 每模块 `packages.Load`（`NeedName|NeedFiles|NeedSyntax|NeedTypes|NeedTypesInfo|NeedDeps`，pattern `./...`，`Tests: false`）
   - 方法节点：遍历 FuncDecl（含带 receiver 的方法与多返回值），行号/源码经 token.FileSet 偏移提取
   - 类型节点：GenDecl 里的 struct/interface，`types.Implements` 计算工作区内满足的接口集合
   - 调用边：遍历函数体 CallExpr（普通调用经 `info.Uses`，方法调用经 `info.Selections`）；接口分派扇出到实现；`go f()`/`defer f()` 记 DIRECT 边；嵌入结构体方法提升自然解析为被嵌入类型的方法（DIRECT）
   - 输出按上述 JSON 契约
4. **脚本** `run-go-analyzer-local.sh`（镜像 run-jvm-analyzer-local.sh：build + exec，日志到 logs/）。
5. `go test ./...`：testdata 夹具覆盖自由函数、方法、接口扇出、goroutine/defer、嵌入提升。

## Phase 3 — code-intelligence 集成

1. **`domain/GoAnalysisOutput.java`**（+ GoTypeSpec/GoMethodNode/GoCallEdge 嵌套 record，Gson 反序列化，模式同 PSI* records）。
2. **`service/GoAnalyzerExecutor.java`**（镜像 PSIExecutor HTTP 模式，精简版）：配置 `octopus.go.service-url`（默认 `http://localhost:8378`）、`timeout-seconds: 600`、`connect-retry 3 次/5s`；含 /workspace 路径映射（拷贝 PSIExecutor 的 10 行私有助手）；失败返回 null。
3. **`service/GoGraphAssembler.java`**：GoAnalysisOutput → (`CallGraph`, `ParseResult`)：
   - 节点：method → MethodNode（returnType = returnTypes 用 ", " 连接；modifiers=""）
   - 边：按签名组 CallEdge（DIRECT/INTERFACE）
   - ParseResult：每类型一个 ParsedClass（struct→CLASS、interface→INTERFACE、parentClass=embedded、interfaces=implements）；每包一个伪 ParsedClass 承载自由函数；ParsedMethod 带 lineStart/lineEnd/sourceCode → **复用 `MetadataStoreService.storeMetadata` 原路径**（is_head/commit 机制、包去重、inheritance_relation 全部白得）
4. **`analyzer/GoProjectAnalyzer.java`**（@Service）：`getLanguage()="GO"`、priority 10、`supports()`=根目录或一级子目录含 go.mod；`scanProjects`=每个 go.mod 一个 DiscoveredProject（buildTool=null、sourceDirectories=[模块目录]，落地时确认 analyzed_project.build_tool/java_version 列可空，不可空则补 DDL）；`analyzeDeep`=工作区级 `goExecutor.execute` 一次 → null 则全部模块记降级（无 JavaParser 等价回退）→ 否则组装、按 sourceFilePath 前缀过滤每模块图，复用 clear→storeNodes→storeMetadata→Phase2 storeEdges（allProjectIds）时序；自带 findOrCreateEntity（键与 JVM 一致：repoConfigId+branch+projectName / 本地按路径）。
5. **配置**：application.yaml 加 `octopus.go` 段；application-docker.yml 加 host.docker.internal:8378。
6. 版本语义直接复用：commit_id 由 LocalGitDetector/change 管线传入，Go 侧无感知。

## Phase 4 — 验证与文档

1. Java 测试：`GoProjectAnalyzerTest`（supports/scan/analyzeDeep 成功与降级路径，mock executor+存储）、`GoGraphAssemblerTest`（签名透传、伪类结构、行号）、Dispatcher 路由测试（Go 目录→GO、Java 目录→JVM、空目录→JVM 兜底）。
2. E2E：/tmp 下建 Go 样例仓库（2 个包 + 接口 + 跨包调用 + goroutine），起 Go 服务 → `POST /api/analysis/local` → 验证：任务 COMPLETED；Neo4j MethodNode/CALLS 数量与扇出边正确；MySQL class_info/method_info 行号齐全；`GET /api/query/...` 调用链在 Go 方法上可用。
3. 文档：architecture.md 加 Go 适配器小节；README 模块表加 go-project-analyzer:8378；docs/sql 若有 DDL 变更则补迁移脚本。

## 明确不做（后续迭代）

- octopus-analysis-scheduler 的 `queue:analysis:go`（需求文档已预留）
- HTTP 路由入口识别（gin/echo/net-http → 合成 HttpRoute 注解行）
- tag 驱动管线的 Go diff 过滤（computeDiff 现仅识别 .java，Go 走 local/change 管线）
- analyzed_project.language 列（build_tool/java_version 为 NULL 即可区分）