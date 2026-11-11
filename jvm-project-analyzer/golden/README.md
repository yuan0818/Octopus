# PSI 抽取 golden 测试（Phase 2.6）

`spring-shop/` 是一个覆盖 Spring 常见隐式调用的最小项目：构造/Setter 注入、接口调用、`@Async`、`@Scheduled`、
`@EventListener`、Spring Data Repository、`@Aspect` 切面。Spring / AspectJ 注解以**同全限定名的桩**放在项目内，
因此 IntelliJ 不需要下载任何依赖即可解析，结果可重复。

`spring-shop.expectations.json` 列出必须抽取到的 Bean、调用边与各类绑定：

- `required`：缺失即视为分析器回归，检查脚本退出码为 1；
- `known_gap`：已知能力缺口（如 `publishEvent` 发布者、AOP 切点匹配），只报告；修复后脚本会提示“可升级为 required”。

运行（需要本机 IntelliJ 与分析服务）：

```sh
./run-jvm-analyzer-local.sh &                     # 启动 PSI 服务 :8377
python3 jvm-project-analyzer/golden/check_golden.py --url http://localhost:8377
```

CI 中由 `.github/workflows/psi-golden.yml` 每晚运行（需在仓库变量 `IDEA_DOWNLOAD_URL` 配置 Linux 版 IntelliJ 2025.3 下载地址）。
首次运行后请核对 `known_gap` 之外的预期是否与实际签名格式一致，再固定为基线。
