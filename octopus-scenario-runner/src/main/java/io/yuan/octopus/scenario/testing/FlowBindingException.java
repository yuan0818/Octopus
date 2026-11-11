package io.yuan.octopus.scenario.testing;

import java.util.*;
import static io.yuan.octopus.scenario.testing.TestingModel.*;

/** Only declared names and type metadata may leave validation; never echo an unknown model reference. */
final class FlowBindingException extends IllegalArgumentException {
    final FlowFailure.BindingIssue issue;

    FlowBindingException(String code, String input, String expectedType, String actualType,
                         String declaredContextKey, List<String> availableContextKeys) {
        super("参数 " + input + "：" + switch (code) {
            case "CONTEXT_TYPE_MISMATCH" -> "需要 " + expectedType + "，上下文类型为 " + actualType;
            case "CONTEXT_NOT_UPSTREAM" -> "引用的输出不属于当前步骤的上游";
            default -> "引用了未声明的上下文（需要 " + expectedType + "）";
        });
        issue = new FlowFailure.BindingIssue(code, input, expectedType, actualType, declaredContextKey,
                availableContextKeys, "只引用当前步骤可用且类型匹配的上下文；预处理动作输出使用 result 并绑定到 preparation.body 或 headers。缺少业务数据时保留空缺并提出具体问题，不创建虚构变量。");
    }

    static boolean compatible(String actual, String expected) {
        return "any".equals(expected) || expected.equals(actual) || "integer".equals(actual) && "number".equals(expected);
    }

    static Map<String, Variable> available(Flow flow, String nodeId) {
        var result = new TreeMap<String, Variable>(flow.initialContext());
        var upstream = FlowFillValidator.ancestors(flow).get(nodeId);
        flow.nodes().stream().filter(n -> upstream.contains(n.id())).forEach(n -> n.outputs()
                .forEach(o -> result.put(o.key(), new Variable(o.type(), o.sensitive()))));
        return result;
    }

    static List<String> matching(Map<String, Variable> available, String expected) {
        return available.entrySet().stream().filter(e -> compatible(e.getValue().type(), expected))
                .map(Map.Entry::getKey).sorted().toList();
    }
}
