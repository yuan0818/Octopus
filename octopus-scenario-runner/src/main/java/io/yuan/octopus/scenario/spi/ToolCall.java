package io.yuan.octopus.scenario.spi;

import java.util.Map;

/**
 * IR 的唯一能力原语：对注册表内工具的一次调用。
 * 参数值为模板（${...} 在执行时按作用域链解析）。
 */
public record ToolCall(String type, Map<String, Object> args) {}
