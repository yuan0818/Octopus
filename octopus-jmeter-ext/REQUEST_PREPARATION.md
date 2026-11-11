# 请求预处理工具

人选择 API 并安排业务路径；AI 为当前节点提出数据绑定与预处理计划。整链任务逐节点调用同一补全接口，计划与步骤记忆一起保存为候选。确认流程后，计划进入 Test IR 和能力锁，由固定 JSR223 模板在 JMeter 子进程执行，执行期间不调用模型。

## 使用

在业务流程中打开一个步骤，找到「请求加密与网关预处理」。可以选择「Platform 加密并签名」「仅网关签名」或自定义工具。展开动作即可编辑参数来源；不需要填写 JSON 或 Groovy。也可以使用「AI 补全整个链路」或单节点补全，结合仓库源码和环境背景生成计划。

使用 Platform 模板时：

1. 在「组装业务数据」添加明文业务字段，分别绑定固定测试值、已声明的上下文或运行时凭据。已填 `body.content` 时，模板优先引用该入参。
2. 确认 `keyPairId`、`appid` 及该测试环境的 MD5 / SHA-256 配置。模板故意留空这三个字段，不推测真实部署配置。
3. `platform.publicKey` 和 `platform.signingSecret` 是凭据引用名称。运行页面将列出这些引用，填写场景服务中对应的环境变量名；也可以改用标记为敏感的流程上下文，在运行页面提供值。密码同样只能使用运行时引用。
4. 检查 Body 与 Header 映射。生成 Body 会替换整个实际发送的请求体；原始 API 契约、原有配置仍保留。签名和实际发送的请求必须引用相同的 Body、appid、requestid、timestamp。
5. 检查补全差异与业务预期后保存、确认、生成场景。请求前的每个动作都会显示在运行结果中。

`/login` 的客户端请求使用公钥包装 AES key，服务端使用私钥解开 `cipher`。不要把服务端私钥复制进测试计划。此版本支持 X.509 `PUBLIC KEY` PEM 或 Base64 DER 公钥，不接受 PKCS#1 `RSA PUBLIC KEY` 或证书文件。

## 工具目录与计划

`GET /api/business-testing/preparation-tools` 返回同一份封闭目录，供 UI 和 LLM 使用。每个工具声明 ID、版本、输入类型、输出类型、敏感参数及协议说明。只有已注册且编译器可执行的工具能进入计划；JMeter 安装状态在运行前另外校验。

| 工具 | 输入 | 输出 |
| --- | --- | --- |
| `time.now@1` | 无 | `value`: Unix 毫秒整数 |
| `uuid@1` | 无 | `value`: UUID 字符串 |
| `string.value@1` | `value`: 标量 | `value`: 字符串 |
| `string.concat@1` | `prefix`, `value`: 字符串 | `value`: 拼接结果 |
| `json.object@1` | 最多 50 个独立绑定字段 | `value`: 对象 |
| `digest@1` | `algorithm`: MD5 / SHA-256，`text` | `value`: UTF-8 摘要，小写十六进制 |
| `platform.encrypt@1` | `content`, `publicKey`, `keyPairId`, `timestamp`, `version` | `body`: 完整加密请求体 |
| `platform.sign@1` | `body`, `query`, `appid`, `requestid`, `timestamp`, `algorithm`, `signingSecret` | `sign`: 签名 |

时间、UUID、摘要等采用与常用 JMeter 函数对应的 Java 实现，通过固定模板执行，避免将模型文本拼进 `${__function(...)}`。此版本不开放任意 Groovy、任意函数名或任意 Java 方法调用。

`FlowNode.preparation` 和 `Step.preparation` 为可选字段：

```json
{
  "tools": [
    {"id":"clock","tool":"time.now","version":"1","inputs":{}},
    {"id":"asText","tool":"string.value","version":"1","inputs":{"value":{"source":"result","value":"clock.value"}}}
  ],
  "body": null,
  "headers": {"timestamp":{"source":"result","value":"asText.value"}}
}
```

绑定来源：`literal` 固定值、`context` 流程变量、`secret` 运行时凭据引用、`input` 已填写的 API 入参、`request` 原始 `body/query/headers` 对象、`result` 前面动作的 `动作ID.输出字段`。不支持表达式、嵌入脚本或前向引用。上下文引用参与依赖和循环检查；AI 只能引用人已规划的上游步骤。

## Platform v1 协议

适配器依据 Platform `0d7fac61b65f544e5b766e85c9af9c3f4dd13d50` 的 `CryptoAspect`、`AESUtils`、`RSAUtils`、`SignatureVerificationFilter`、`MD5Utils` 和 `SHA256Util` 实现。是否适用于当前 API，仍需根据入口路由、注解及实际环境配置确认。

加密动作每次使用 SecureRandom 生成 16 位 ASCII AES key；以 AES/ECB/PKCS5Padding 加密业务 JSON 为 Base64 `data`，以 RSA/ECB/PKCS1Padding 公钥加密 AES key 为 Base64 `cipher`。返回体还包含 `keyPairId/timestamp/version`，不包含明文 `content`。ECB 与摘要签名是对现有协议的兼容实现。

签名合并顺序是特定 Header → 解码后的 Query → 最终 Body，Query 严格重现网关的 `URI.getQuery()` 后再次 `HttpUtil.decodeParamMap` 的行为（复用相同版本的 UrlQuery），后者覆盖同名键；按 TreeMap 排序并拼接 `key=value&`，末尾拼接 `key=signingSecret` 后计算 MD5 或 SHA-256。嵌套 Map 在 MD5 分支排序，在 SHA-256 分支保留顺序；使用 Jackson 重现网关的 JSON 与数值语义。此协议不是 HMAC。时间戳只生成一次，通过结果绑定在 Header、Body 和签名中复用。

本期保留现有响应提取和上下文传递机制；不包含通用响应解密、证书上传、任意加密算法或动态协议代码生成。以后新增协议需要新增版本化适配器及兼容性测试。

## 执行、失败与恢复

生成顺序是 `guard:步骤` → `prepare:步骤:动作` → `prepare:步骤:request` → HTTP → 响应提取/检查 → 数据验证。JMeter 的线程设为错误即停止；预处理失败不会发送该步骤或后续步骤的请求。Kafka 的调用前观察依旧位于 HTTP 之前。

错误使用固定结果码，例如 `PREPARE_PUBLIC_KEY_INVALID`、`PREPARE_CREDENTIAL_MISSING`、`PREPARE_HEADER_INVALID`，并显示固定中文原因。这些运行故障结论为 `INCONCLUSIVE`；业务断言不符仍为 `FAIL`。日志、JMX、JTL、IR 和步骤记忆不记录运行时凭据、明文加密输入或加密结果。

所有中间结果限定在当前 JMeter 线程与步骤。最终请求组装后删除预处理中间对象引用。重新运行会重新生成动态值，不复用上次的签名或密文；不会自动重发已经发送的业务请求。AI 任务恢复仍沿用现有持久化检查点，计划改变或工具目录版本改变会使相关步骤记忆过期。

## 安装和验证

执行 `./gradlew :octopus-jmeter-ext:distribution`，将 `build/jmeter-extension/lib` 合并到独立的 JMeter 5.6.3 安装。主 JAR 在 `lib/ext`，Gson、Hutool core 5.8.16 等依赖在 `lib`。Jackson 复用 JMeter 自带版本，避免重复安装。运行器检查主 JAR 的 `Octopus-Preparation-Version: 1` 和所需类、依赖。

`PreparationValidationTest` 验证类型、能力、依赖、签名绑定和旧版 hash 兼容；`StepSuggestionServiceTest` 验证模型工具上下文和节点候选边界；`PreparationJmeterIntegrationTest` 使用真实 JMeter CLI 调用临时本地 HTTP fixture，验签、解密、传递登录结果，并验证错误公钥与缺失凭据时没有网络请求及产物不泄漏测试凭据。它不调用真实 Platform 环境。
