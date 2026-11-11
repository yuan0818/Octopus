package io.yuan.octopus.scenario.testing;

/** Keep model follow-up questions about credential readiness, never requests to paste secrets into prose. */
final class ModelKnowledgePolicy {
    private ModelKnowledgePolicy() {}
    static String question(String text) {
        if (text != null && text.matches("(?is).*(secret|password|私钥|密钥|密码|凭据).*")
                && text.matches("(?s).*(提供|补充|获取|具体值|真实值|是多少|的值|值或).*"))
            return "请在独立凭据配置中确认所需的运行时引用与可用性；不要把真实值写入业务背景或模型上下文。";
        return text;
    }
}
