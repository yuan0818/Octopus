package io.yuan.octopus.scenario.spi;

import java.util.Map;

/**
 * 上下文供给插件：为 ${...} 模板解析提供某一作用域的值。
 * 实现：env.profile（环境档案）、var.extracted（步骤提取变量）、fn（运行时函数）。
 */
public interface ContextProvider {

    /** 作用域前缀（如 env / var / fn），模板 ${scope.name} 按此路由。 */
    String scope();

    /** 解析作用域内的名字；未定义返回 null（由解析器决定报错或留空）。 */
    Object resolve(String name, Map<String, Object> scopeContext);
}
