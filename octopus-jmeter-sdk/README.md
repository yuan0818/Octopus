# Octopus JMeter 扩展 SDK

用 Java 编写符合 JMeter 规范的函数扩展，打包为 JAR 上传到平台后，自动注册为可供人和 LLM 发现、调用的工具。

## 依赖

```gradle
dependencies {
    compileOnly 'io.yuan:octopus-jmeter-sdk:0.0.1-SNAPSHOT'      // 或直接引用 octopus-jmeter-sdk-1.jar
    compileOnly 'org.apache.jmeter:ApacheJMeter_core:5.6.3'
    compileOnly 'org.apache.jmeter:jorphan:5.6.3'
}
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }  // 字节码不高于运行器 JVM（Java 21）
```

SDK 与 JMeter 都只在编译期依赖：运行时由 JMeter 安装（`lib/octopus-jmeter-sdk-1.jar`，随 `octopus-jmeter-ext` 的 distribution 安装）提供。第三方依赖请重定位（shade）到你自己的带版本段的包中。

## 写一个扩展

```java
package com.acme.functions.v1_0_0;          // 必须含 .functions.（JMeter 发现规则）与版本段 v1_0_0

import io.yuan.octopus.extension.sdk.*;

@OctopusTool(name = "acme.sign", version = "1.0.0",
        summary = "按 ACME 网关规则计算请求签名",
        description = "body 与 timestamp 拼接后计算 HMAC-SHA256，输出小写十六进制。",
        whenToUse = "调用 ACME 网关下需要 X-Sign 请求头的接口",
        whenNotToUse = "非 ACME 网关接口；回应验签",
        determinism = Determinism.PURE, stages = {Stage.REQUEST_TRANSFORM})
@ToolParam(index = 0, name = "body", type = ParamType.STRING, description = "最终发送的请求体 JSON")
@ToolParam(index = 1, name = "timestamp", type = ParamType.INTEGER, description = "毫秒时间戳，须与请求头一致")
@ToolParam(index = 2, name = "secret", type = ParamType.STRING, sensitive = true, description = "签名密钥")
@ToolOutput(type = ParamType.STRING, format = "hex-64", description = "X-Sign 请求头的值")
@ToolExample(args = {"{\"a\":1}", "1700000000000", "<test-secret>"}, output = "…精确输出…", description = "最小请求体")
@ToolExample(args = {"{}", "1700000000000", "<test-secret>"}, output = "…精确输出…", description = "空请求体")
@ToolError(code = "SIGN_BODY_INVALID", description = "body 不是合法 JSON")
public class AcmeSign extends OctopusFunction {
    @Override
    protected Object compute(Args args) throws ToolException {
        // 只依赖参数：不读写 JMeter 变量 / 属性，不读上一个采样，不访问文件或网络。
        ...
    }
}
```

`OctopusFunction` 继承 `AbstractFunction`，根据注解自动提供：

- `getReferenceKey()` = `__acme_sign_v1_0_0`（含版本，使多个版本可在同一 JMeter 中共存）；
- `getArgumentDesc()`、参数个数检查、类型转换、`enumValues` / `pattern` / `min` / `max` / `maxLength` 校验；
- 输出序列化（OBJECT 输出为 JSON 文本），`ToolException(code)` 映射为声明的错误码。

也可以直接继承 `AbstractFunction`，但引用键与参数描述须与注解一致（上传时在隔离进程中核对）。

## 元数据契约

| 注解 | 要点 |
|---|---|
| `@OctopusTool` | `name`（小写命名空间名称）、`version`（语义化版本，发布后不可修改）、`summary` ≤120 字、`description` ≤2000 字、`whenToUse`、`whenNotToUse`、`determinism`（PURE / CLOCK / RANDOM）、`stages` |
| `@ToolParam` | `index` 从 0 连续；必填在前；敏感参数只能是 STRING，且示例只能用 `<占位值>` |
| `@ToolOutput` | 类型、格式、说明；OBJECT 可附 JSON Schema |
| `@ToolExample` | 至少 2 个，覆盖全部必填参数；PURE 必须给精确输出（上传时执行两次比对），CLOCK / RANDOM 用 `match = REGEX` |
| `@ToolError` | 错误码（大写）与触发条件、修正方式 |

`EXPECTED_VALUE` 阶段只允许 PURE。这些字段同时生成平台中的文档页和 LLM 工具定义（参数 JSON Schema），二者不会不一致；工具定义有长度预算（约 6,000 字符）。

## 上传后发生什么

1. 读取字节码与注解（不加载、不执行代码），列出识别到的工具；
2. JMeter 合规与元数据契约校验；
3. 安全扫描：执行进程、网络、脚本引擎、JNDI、`System.exit` 等直接拒绝；文件、反射、线程、读写 JMeter 变量 / 属性作为警告展示；
4. 与已发布扩展的版本、类名冲突检查；
5. 在隔离的 JMeter 进程中按 JMeter 规则发现函数并执行全部示例；
6. 全部通过后可直接发布（无人工审核），JAR 安装到 `JMETER_HOME/lib/ext`，运行前按 SHA-256 核对。

运行时扩展与 JMeter 同进程，本期不做运行时隔离。
