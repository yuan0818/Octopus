package io.yuan.octopus.common;

/**
 * 方法签名规范化：不同分析引擎（PSI 用 ", "、JavaParser 用 ","）对参数分隔符的
 * 约定不一致，导致跨版本/跨引擎的签名身份匹配失败（同一方法被认成两个）。
 * 统一规范：参数分隔符为 ","（无空格）。所有签名写入与比较都必须经过本类。
 */
public final class SignatureNormalizer {

    private SignatureNormalizer() {}

    /** 规范化签名：去掉逗号后的空白（仅参数列表内的分隔，安全——泛型内 ", " 同样规范化）。 */
    public static String canonical(String signature) {
        if (signature == null || signature.isEmpty()) {
            return signature;
        }
        return signature.replace(", ", ",");
    }

    /** 身份比较用键。 */
    public static String key(String signature) {
        return canonical(signature);
    }
}
