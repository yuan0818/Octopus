# Octopus JMeter 扩展

除数据库与中间件验证外，还支持 [请求加密、签名及预处理工具](REQUEST_PREPARATION.md)。

业务描述 → LLM 验证草稿 → 人确认 TestPoint → Test IR → JMX → JMeter 扩展执行。LLM 不连接数据库、不执行 SQL；运行时也不再次调用 LLM。

## 在工作台使用

1. 在「项目背景」选择项目、模块和环境，保存连接。用途与数据结构中写清楚表与字段、Redis key 格式，或 Kafka topic、消息 key 与消息字段。主机与凭据填独立字段。
2. 在 Flow 的接口步骤里点击「验证数据库 / Redis / Kafka」，选择背景中的连接，填写业务目标，例如“支付后，orders 表中本次 order.id 对应的 status 为 PAID”。
3. 点击「AI 生成验证」，检查表、字段、参数绑定与预期值。可以直接编辑表单，也可以手工配置。缺少结构信息时模型应返回待补充问题；不会自动连接、扫描数据库或补造表结构。
4. 点击「加入当前测试点」，保存并确认整个 Flow。修改查询或预期值会改变 TestPoint 的 oracle hash，需要重新确认版本与生成场景。
5. 运行时填写环境标识和测试地址。连接按已确认计划中的项目/模块/连接 ID，在该环境解析；不会回退到其他环境。模块覆盖替换整个连接，未覆盖的连接继承项目凭据。
6. 报告区分接口响应检查、Kafka 观察起点和每项数据验证。业务预期未满足是 FAIL；连接/权限/结构/扩展或观察范围故障是 INCONCLUSIVE。

初始数据与当前步骤/上游步骤的输出均可作为查询参数或预期值。引用其他步骤输出时自动参与依赖排序和循环检测。本步骤的输出在 HTTP 响应提取成功后供验证使用。

## 已实现范围

| 类型 | 查询 | 返回值与限制 |
| --- | --- | --- |
| MySQL / PostgreSQL | 单表参数化 SELECT，明确字段或 COUNT(*)，1–10 个 AND 过滤条件 | `$.rows[0].status`、`$.rows[0].count`、`$.rowCount`；最多 100 行，超限不作通过判断 |
| Redis | GET、HGET、EXISTS、TTL | `$.value` 或 `$.value.status`；GET/HGET 的 JSON 自动解析，EXISTS 为布尔值、TTL 为秒数；明确 key，不执行扫描/写入/脚本 |
| Kafka | 指定 topic、精确匹配消息 key、验证 JSON 字段 | `$.value.status`；接口调用前记录各分区日志末端，之后读取已提交消息；最多 100 分区、1000 条记录；不创建 topic、不加入消费组、不提交消费位点 |

预期条件支持等于、存在且非 null、大于、大于等于。普通数值按数值比较。SQL/Redis 在 1–30 秒观察窗口内轮询，Kafka 在窗口内等待新消息；每次连接/查询 I/O 另有 5 秒超时，整个运行仍受 JMeter 进程总超时和取消控制。结果大小超过上限会报告无法确定，不截取部分数据当成成功。

SQL 由封闭的结构化查询规则生成，标识符校验并引用，值全部经 PreparedStatement 绑定；JDBC 连接设为只读事务且结束时回滚。建议为测试验证使用只读账号。暂不接受任意 SQL、JOIN、子查询、用户函数、DDL/DML，其他数据库及消息系统仍仅保存背景资产。

TLS 校验证书和主机；Kafka 支持无凭据、TLS、SASL/PLAIN + TLS、SCRAM-SHA-256/512 + TLS。证书使用驱动/JVM 的信任配置，本期没有上传私有 CA、客户端证书或 SSH 隧道界面。MySQL 普通连接不会开启不安全的 RSA 公钥自动获取；使用认证所需的 TLS/信任配置。Redis 暂支持单节点 DB 0–15，不支持 Cluster/Sentinel。

## 安装与编译

使用 Java 21、JMeter 5.6.3：

```sh
./gradlew :octopus-jmeter-ext:distribution
# 将 build/jmeter-extension 的内容复制到一个独立的 JMeter 5.6.3 安装目录：
cp -R octopus-jmeter-ext/build/jmeter-extension/lib/. "$JMETER_HOME/lib/"
```

扩展主 JAR 位于 `lib/ext/octopus-jmeter-ext-1.jar`，协议驱动位于 `lib`。JMeter 原有 SLF4J、commons-pool2 继续复用；不要向原 JMeter 目录混装不同版本的同名驱动。场景服务启动时设置 `JMETER_HOME` 指向该安装。能力注册表声明 `verify.sql`、`verify.redis`、`verify.kafka`；执行前检查扩展版本与依赖，编译不会建立连接。

JMX 使用 JMeter 原生 JSR223 Sampler 调用这个扩展，脚本是固定编译模板，模型文本通过 Base64 JSON 参数传递。协议连接、读查询、消息观察和断言发生在 JMeter 子进程，不是场景服务的独立协议执行器。

## 凭据与产物

项目背景里的凭据仍使用 AES-GCM 加密；只有执行服务在明确环境下解析所需连接并解密。JMeter 接收私有运行目录中的 `connections.json`（0600），通过 `-Joctopus.connectionsFile` 注入文件路径，结束、失败、超时和取消均删除；原有初始上下文文件同样删除。进程被操作系统强杀时无法保证 finally 执行，运维清理已停止运行目录时需一并清除遗留临时文件。

Flow、IR、JMX、能力锁、模型输入及结果报告均不包含连接用户名/密码。协议异常只返回固定原因，Kafka 客户端日志关闭；查询结果与消息内容不复制进 SampleResult/JTL。报告保留检查 ID、对应业务步骤、耗时、通过/不符/无法验证，查询与预期可在已确认 Flow 中查看。

## 开发接口

- `POST /api/business-testing/verifications/suggest`：逻辑连接 source、参考 environment、description、变量名→类型、可选 model。返回 plan、可读 preview、questions、assumptions、实际 model。
- `POST /api/business-testing/verifications/preview`：plan + environment；校验结构和连接类型并返回预览，不查询数据。
- Review Agent 内部 `POST /api/business-flow/verification` 复用既有模型配置与适配器。
- 业务检查使用 `Assertion.kind=DATASTORE_VERIFY`，expected 保存 `VerificationPlan`。它仍属于 TestPoint，沿用已有确认、版本和 oracle 锁；旧 Flow 的 JSON 字段及 hash 不变。
- 执行 Profile 新增 `environment`；没有数据验证的旧场景仍可省略。

## 验证

单元测试覆盖封闭查询规则、参数绑定与注入反例、真实 H2 行查询、范围上限、Kafka 关联与历史消息、凭据继承及隔离、模型输入投影、IR 依赖与能力锁。

`DatastoreJmeterIntegrationTest` 通过真实 JMeter CLI 连接明确的临时本地 MySQL 23306、Redis 26379、Kafka 29092。只有 `OCTOPUS_VERIFY_FIXTURES=true` 时才执行，不能指向已有业务环境。测试通过 HTTP fixture 更新测试数据/发布消息，再读取验证；覆盖成功、业务不符、历史消息和错误凭据，并检查临时文件删除与产物无密码。

参考：[JMeter JSR223 Sampler](https://jmeter.apache.org/usermanual/component_reference.html#JSR223_Sampler)、[Kafka Consumer API](https://kafka.apache.org/40/javadoc/org/apache/kafka/clients/consumer/KafkaConsumer.html)、[Redis Jedis 连接](https://redis.io/docs/latest/develop/clients/jedis/connect/)。
