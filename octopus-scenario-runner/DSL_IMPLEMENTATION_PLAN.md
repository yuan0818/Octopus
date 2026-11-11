# 业务 API 测试 DSL —— 实施计划

| 项 | 内容 |
|---|---|
| 依据 | [DSL_REQUIREMENTS.md](DSL_REQUIREMENTS.md) v0.6（已决：YAML 语法、授权轮询、无运行时隔离、无人工审核、仅函数扩展点） |
| 日期 | 2026-10-14 |
| 原则 | 先确定性、后 LLM；每个增量都可独立交付并保持现有 Flow / IR 哈希不变 |

## 1. 增量划分

需求中的 P0 范围较大，按依赖关系拆为三个增量。每个增量结束时都能端到端使用。

| 增量 | 目标 | 主要需求 |
|---|---|---|
| **I1 确定性核心（本次）** | 使用者写“显式路径 + 显式依赖 + 精确断言”的 YAML DSL，平台**不调用 LLM** 即可转换为可确认的 ordered Flow；JAR 扩展可上传、自动校验并注册为 LLM 可发现的工具；轮询必须经过 API 级授权 | FR-LANG（L0 结构、L2、L3 子集）、FR-DERIVE-01/02、FR-LIN、FR-POLL、FR-FN、FR-EXT、FR-RT-01/03/05、FR-API-01–03/09–11 |
| **I2 LLM 推导与语义** | 未声明部分（测试数据、非同名依赖、自然语言验证、`db:` 自然语言）由 LLM 在上下文包内推导；GWT 角色与判定；回应解码阶段；技术视图 | FR-DERIVE-03/10–16、FR-CTX（A/B/C/E/F 层、知识卡、上下文包）、FR-ROLE、FR-DEC、FR-UI-04/07、FR-CONV-06 |
| **I3 规模化** | 前置复用、数据驱动、扩展断言、仓库同步与 CLI、扩展模板工程 | FR-FIX-02/03、FR-LANG-51、FR-DEP、FR-GIT-03/04/06、FR-EXT-29 |

## 2. I1 工作包

| 编号 | 工作包 | 交付物 | 需求 |
|---|---|---|---|
| WP1 | 扩展 SDK | 新模块 `octopus-jmeter-sdk`：`@OctopusTool`、`@ToolParam`、`@ToolOutput`、`@ToolExample`、`@ToolError` 注解，`Determinism` / `Stage` / `ParamType` 枚举，`OctopusFunction` 基类（继承 `AbstractFunction`，按注解解析参数、类型转换、错误码映射） | FR-EXT-10–13 |
| WP2 | JMeter 运行时 | `octopus-jmeter-ext`：`ExtensionTools`（运行时扩展目录，读取 `octopus-extensions.json`）、`ExtensionInvoker`（按类名实例化 JMeter 函数，参数按 JMeter `FunctionParser` 规则转义，不拼接函数字符串）、`ExtensionProbe`（隔离验证进程入口：按 JMeter `ClassFinder` 规则发现、核对引用键与参数描述、执行示例）；`PreparationTools.get(id, version)` 支持多版本 | FR-FN-03/08、FR-EXT-04/24/26/28 |
| WP3 | 扩展注册 | `scenario.extension` 包：`JarInspector`（Spring ASM 读取字节码与注解，不加载类；JMeter 合规、元数据质量、风险扫描）、`ExtensionVerifier`（子进程执行 Probe，超时、临时目录、清空环境变量）、`ToolExtension` 实体与仓库、`ExtensionService`（上传 → 解析 → 校验 → 可发布 / 拒绝；发布写入分发目录与清单；启动时加载）、`ExtensionController` | FR-EXT-20–28、FR-EXT-30 |
| WP4 | 统一函数目录 | `FunctionCatalogService`：平台工具 + JMeter 内置函数白名单 + 已发布扩展；`search` / `describe` / `try`；由同一元数据生成 JSON Schema（LLM 工具定义）与 Markdown 文档 | FR-FN-01/02/05/06/20/22/25/26 |
| WP5 | 轮询授权 | `PollAuthorization` 实体、服务与接口；`Poll` 增加可选 `authorizationRef`（为空时序列化省略，旧哈希不变）；生成场景与运行前校验授权（缺失 / 撤销 / 超出 → INCONCLUSIVE） | FR-POLL-01–06 |
| WP6 | DSL 核心 | `scenario.dsl` 包：基于 SnakeYAML 节点树（保留行列号、不构造对象）的解析器，AST，值表达式（`${alias.field}`、`${env.x}`、`${secret.x}`、`${input.x}`、`${fn.name(...)}`），精确断言解析，lint（结构、线性禁止项、引用、单一生产者、等待 / 轮询约束、规模上限），规范化 YAML 渲染 | FR-LANG-01–05/10–12/20/23/30–35/40/50、FR-DERIVE-01、FR-LIN-02 |
| WP7 | DSL 转换与渲染 | `DslTranslator`：路径解析（固定分析版本）→ 契约端口 → ordered Flow（全部 SERIAL）；确定性推导（唯一成功状态码、同名同类型依赖）；溯源；`FlowDslRenderer`：Flow → DSL（只输出声明项，PARALLEL 报错）；`DslController`：`lint` / `translate` / `render` / `schema` | FR-LANG-11、FR-DERIVE-02、FR-CONV-01/02、FR-RT-01/03/05、FR-API-01–03 |
| WP8 | 线性守卫 | `JmxLinearGuard`：编译后检查 JMX 只含白名单元素；While 只允许出现在授权轮询步骤 | FR-LIN-06 |
| WP9 | LLM 函数发现 | 步骤补全支持工具调用回合：模型返回 `toolCalls`（`search_functions` / `describe_function` / `try_function`），场景服务执行并回传，最多 10 回合；扩展只以结构化 `{tool, version, inputs}` 进入预处理计划 | FR-FN-20–23/25/27 |
| WP10 | 前端 | 「DSL 工作台」（编辑、实时 lint、转换预览与溯源、保存为 Flow 草稿、从 Flow 导出）、「JMeter 扩展」（上传、校验报告、文档预览、发布 / 停用 / 弃用、函数目录）、「轮询授权」 | FR-UI-01/02/05、FR-UI-06（部分） |
| WP11 | 迁移与文档 | SQL 迁移、`BUSINESS_TESTING.md` 章节、扩展开发指南、测试 | — |

### 2.1 关键设计决定（I1）

1. **DSL 只产出 ordered Flow**：每个 DSL 步骤一个 SERIAL `FlowStep`、一个 `FlowNode`；每个场景一个 Flow 与一个 TestPoint。不生成 PARALLEL、DAG 依赖或任何控制器。
2. **扩展通过请求预处理阶段执行**：I1 支持 `REQUEST_VALUE` 与 `REQUEST_TRANSFORM` 两个阶段，作为 `PreparationPlan.Call(tool="x.<name>", version="<semver>")` 进入现有预处理模板；`RESPONSE_DECODE` 与 `EXPECTED_VALUE` 的元数据与校验在 I1 完成，运行时接入在 I2 与回应解码阶段一起完成。
3. **扩展不拼接 `${__fn()}` 文本**：运行时按清单中的类名实例化函数，每个参数构造为 `CompoundVariable`，参数先按 JMeter `FunctionParser` 的转义规则处理（`\`、`$`、`,`），因此任何字面量都不会被解释为变量或函数。
4. **上传时不执行扩展代码**：解析用 Spring 内置的 ASM 读取字节码；示例只在独立子进程中执行。
5. **多版本共存**：引用键与包名必须包含版本标识（例如 `__acme_sign_v1_0_0`、`com.acme.functions.v1_0_0`），发布后不可修改。
6. **轮询授权按 API 稳定身份**：`projectId + HTTP 方法 + 路径 + 方法签名`，跨提交继承；Flow 中的 `poll.authorizationRef` 固定到授权 ID 与版本。
7. **兼容**：`Poll.authorizationRef`、扩展目录均为可选字段；`TestingJson` 对空值省略，已有 Flow 哈希不变。已确认的旧轮询流程在补充授权前运行判定 INCONCLUSIVE。

### 2.2 I1 不包含（明确延后）

自然语言验证与 `db:` / `redis:` / `kafka:` 验证的转换（I1 lint 提示“需要推导”，草稿标记待补充）；LLM 测试数据生成与非同名依赖推导；GWT 角色写入模型与判定语义；回应解码阶段；API 知识卡与数据影响分析；技术视图。以上全部在 I2。

## 3. 验证策略

| 层次 | 方式 |
|---|---|
| DSL 解析 / lint / 渲染 | 单元测试（含黄金样例：每条错误码一例；渲染再解析一致） |
| 转换 | 单元测试：假 API 解析器 + 真实 `IrValidator` 规则；生成的 Flow 通过 `ScenarioPlanner.validateDraft` |
| 扩展检查 | 单元测试：测试中动态生成的 JAR（合规 / 包名错误 / 参数个数不符 / 危险调用 / 元数据缺失） |
| 运行时转义与调用 | 使用真实 JMeter 5.6.3 库的测试（`CompoundVariable` 解析含 `,`、`$`、`${`、`\` 的参数） |
| 线性守卫 | 编译现有样例场景后检查；人为插入 IfController 必须失败 |
| 轮询授权 | 单元测试：无授权、超出授权、撤销、合法 |
| 回归 | `./gradlew test` 全量；前端 `tsc`、`eslint`、`node --test` |

## 4. 后续增量概要

- **I2**：上下文包组装与追溯（FR-CTX-20–24）、API 定义 B/C/E/F 层（字段语义、校验约束、数据影响、调用约定规则）、知识卡；推导任务复用整链补全的任务 / 检查点 / 租约；角色推导与 `FlowNode.role`、GIVEN 失败 → INCONCLUSIVE；回应解码阶段与扩展 `RESPONSE_DECODE` / `EXPECTED_VALUE` 运行时；技术视图与逐项确认。
- **I3**：通用前置、`examples` 数据驱动、扩展比较运算与 EACH、仓库同步 / 冲突 / CLI、扩展模板工程与本地校验命令。

## 5. I1 完成状态（2026-10-14）

| 工作包 | 状态 | 主要文件 |
|---|---|---|
| WP1 SDK | 完成 | `octopus-jmeter-sdk/`（注解、`OctopusFunction`、`ToolNames`、`Json`） |
| WP2 运行时 | 完成 | `octopus-jmeter-ext/.../extension/runtime/`（`ExtensionTools`、`ExtensionInvoker`、`ExtensionProbe`、`BuiltinFunctions`）；`PreparationTools.get(id, version)`、`PreparationRuntime` 扩展分派 |
| WP3 扩展注册 | 完成 | `scenario/extension/`（`JarInspector`、`ExtensionVerifier`、`ExtensionService`、`ExtensionController`、`ExtensionBundle`） |
| WP4 函数目录 | 完成 | `FunctionCatalog`、`FunctionCatalogService`、`FunctionCatalogController` |
| WP5 轮询授权 | 完成 | `PollAuthorization*`、`PollAuthorizationRules`；`Poll.authorizationRef`；`ScenarioPlanner.validateFlow` 与 `BusinessTestExecutionService.run` 校验 |
| WP6 DSL 核心 | 完成 | `scenario/dsl/`（`DslParser`、`DslExpressions`、`DslChecker`、`DslRenderer`、`DslModel`）；`resources/testing/dsl-schema.json` |
| WP7 转换与渲染 | 完成 | `DslTranslator`、`FlowDslRenderer`、`DslService`、`DslController`、`DslSourceRecord` |
| WP8 线性守卫 | 完成 | `JmxLinearGuard`（`JmxCompiler` 序列化前调用）；`JmeterProcessExecutor` 关闭传输重试 |
| WP9 LLM 函数发现 | 完成 | `FunctionToolLoop`；`StepSuggestionService` 工具回合；review-agent `STEP_PROMPT` 协议说明 |
| WP10 前端 | 完成 | `pages/Dsl`、`pages/Extensions`、`api/dsl.ts`；导航与路由 |
| WP11 迁移与文档 | 完成 | `db/migrations/20261014_dsl_extensions_poll_authorization.sql`、`BUSINESS_TESTING.md`、`octopus-jmeter-sdk/README.md` |

**验证**：纯逻辑部分（SDK、扩展运行时、JAR 检查、函数目录、轮询规则、DSL 解析 / 转换 / 往返、线性守卫）以 Java 11 子集编写，已在沙箱用 javac 编译并运行 45 个单元测试，全部通过；扩展运行时测试使用真实 JMeter 5.6.3 库（`ClassFinder` 发现、参数转义、内置函数调用），隔离验证子进程以真实 JMeter 安装执行。Spring 服务、控制器及 Java 21 修改仅通过语法解析，需运行 `./gradlew test`。前端 `tsc`、`eslint`、66 个测试通过。

**已知限制（I1）**：函数与文本拼接只能用于请求头和请求体顶层字段；`${env.x}` 只能绑定契约中声明的参数；路径解析按“方法 + 路径模板”在所选提交中精确匹配，多实现时需 `service:`；回应路径相对回应体根（尚无包装层解包规则）。
