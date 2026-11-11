package io.yuan.octopus.preparation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import java.util.function.Function;
import static io.yuan.octopus.preparation.PreparationPlan.*;

/** Runs in JMeter only. Never logs arguments, keys, plaintext, ciphertext or signing material. */
public final class PreparationRuntime {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final SecureRandom RANDOM=new SecureRandom();
    public static final class Failure extends RuntimeException {
        public final String code;
        public Failure(String code) { super(code); this.code=code; }
    }
    public static Object resolve(Value value, Map<String,Object> context, Map<String,Object> inputs,
                                 Map<String,Object> request, Map<String,Object> results, Function<String,String> secrets) {
        Object result;
        String key=String.valueOf(value.value());
        result=switch(value.source()) {
            case "literal" -> value.value();
            case "context" -> context.get(key);
            case "input" -> inputs.get(key);
            case "request" -> request.get(key);
            case "result" -> results.get(key);
            case "secret" -> secrets.apply(key);
            default -> throw new Failure("PREPARE_BINDING_INVALID");
        };
        if(result==null || "secret".equals(value.source()) && String.valueOf(result).isBlank())
            throw new Failure("secret".equals(value.source()) ? "PREPARE_CREDENTIAL_MISSING" : "PREPARE_INPUT_MISSING");
        return result;
    }
    public static Map<String,Object> execute(String tool,String version,Map<String,Object> args) {
        if(io.yuan.octopus.extension.runtime.ExtensionTools.isExtension(tool)) {
            var entry=io.yuan.octopus.extension.runtime.ExtensionTools.find(tool,version).orElseThrow(() -> new Failure("PREPARE_TOOL_UNAVAILABLE"));
            try { return Map.of("value",io.yuan.octopus.extension.runtime.ExtensionInvoker.invoke(entry,args)); }
            catch(io.yuan.octopus.extension.runtime.ExtensionFailure e) { throw new Failure(e.code); }
        }
        try {
            var definition=PreparationTools.get(tool);
            if(!definition.version().equals(version)) throw new Failure("PREPARE_TOOL_UNAVAILABLE");
            if(!tool.equals("json.object") && !args.keySet().equals(definition.inputs().keySet())) throw new Failure("PREPARE_INPUT_INVALID");
            if(JSON.writeValueAsBytes(args).length>1_000_000) throw new Failure("PREPARE_INPUT_TOO_LARGE");
            return switch(tool) {
                case "time.now" -> Map.of("value",System.currentTimeMillis());
                case "uuid" -> Map.of("value",UUID.randomUUID().toString());
                case "json.object" -> Map.of("value",new LinkedHashMap<>(args));
                case "string.concat" -> Map.of("value",text(args,"prefix")+text(args,"value"));
                case "string.value" -> {
                    Object v=args.get("value"); if(v instanceof Map || v instanceof List || v==null) throw new Failure("PREPARE_INPUT_INVALID");
                    yield Map.of("value",String.valueOf(v));
                }
                case "digest" -> Map.of("value",digest(text(args,"algorithm"),text(args,"text")));
                case "platform.encrypt" -> encrypt(args);
                case "platform.sign" -> Map.of("sign",sign(args));
                default -> throw new Failure("PREPARE_TOOL_UNAVAILABLE");
            };
        } catch(Failure e) { throw e; }
        catch(Exception e) { throw new Failure("PREPARE_CRYPTO_FAILED"); }
    }
    private static Map<String,Object> encrypt(Map<String,Object> args) throws Exception {
        var keyText=new StringBuilder(); String alphabet="0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        for(int i=0;i<16;i++) keyText.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        byte[] key=keyText.toString().getBytes(StandardCharsets.UTF_8);
        try {
            var pem=text(args,"publicKey").replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
            PublicKey publicKey;
            try { publicKey=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem))); }
            catch(Exception e) { throw new Failure("PREPARE_PUBLIC_KEY_INVALID"); }
            var aes=Cipher.getInstance("AES/ECB/PKCS5Padding"); aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"));
            byte[] plaintext=JSON.writeValueAsBytes(object(args,"content"));
            String data;
            try { data=Base64.getEncoder().encodeToString(aes.doFinal(plaintext)); } finally { Arrays.fill(plaintext,(byte)0); }
            var rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding"); rsa.init(Cipher.ENCRYPT_MODE,publicKey);
            var body=new LinkedHashMap<String,Object>();
            body.put("cipher",Base64.getEncoder().encodeToString(rsa.doFinal(key))); body.put("data",data);
            if(text(args,"keyPairId").isBlank()) throw new Failure("PREPARE_INPUT_MISSING");
            body.put("keyPairId",text(args,"keyPairId")); body.put("timestamp",positiveInteger(args,"timestamp")); body.put("version",positiveInteger(args,"version"));
            return Map.of("body",body);
        } finally { Arrays.fill(key,(byte)0); }
    }
    private static String sign(Map<String,Object> args) throws Exception {
        String algorithm=text(args,"algorithm");
        if(!Set.of("MD5","SHA-256").contains(algorithm)) throw new Failure("PREPARE_ALGORITHM_UNSUPPORTED");
        String appid=text(args,"appid"), requestid=text(args,"requestid"), timestamp=text(args,"timestamp");
        if(appid.isBlank() || requestid.isBlank() || !timestamp.matches("[0-9]+")) throw new Failure("PREPARE_INPUT_INVALID");
        var values=new TreeMap<String,Object>(); values.put("requestid",requestid); values.put("appid",appid); values.put("timestamp",timestamp);
        values.putAll(gatewayQuery(object(args,"query")));
        // Parse the exact wire JSON with the same number/Map semantics used by Gateway's ObjectMapper.
        values.putAll(JSON.readValue(JSON.writeValueAsString(object(args,"body")),LinkedHashMap.class));
        var result=new StringBuilder();
        for(var e:values.entrySet()) {
            Object v=e.getValue();
            if(v instanceof Map m) v=JSON.writeValueAsString(algorithm.equals("MD5")?new TreeMap<>(m):m);
            result.append(e.getKey()).append('=').append(v).append('&');
        }
        result.append("key=").append(text(args,"signingSecret"));
        return digest(algorithm,result.toString());
    }
    private static String digest(String algorithm,String value) throws Exception {
        if(!Set.of("MD5","SHA-256").contains(algorithm)) throw new Failure("PREPARE_ALGORITHM_UNSUPPORTED");
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private static Map<String,Object> gatewayQuery(Map<String,Object> values) {
        // Gateway uses URI.getQuery() followed by HttpUtil.decodeParamMap, not getRawQuery().
        // Reproduce both decoding stages from the compiler's raw query name/value pairs.
        if(values.isEmpty()) return Map.of();
        var pairs=new ArrayList<String>();
        values.forEach((key,value) -> pairs.add(encode(key)+"="+encode(String.valueOf(value))));
        String decoded=java.net.URI.create("http://localhost/?"+String.join("&",pairs)).getQuery();
        var query=new LinkedHashMap<String,Object>();
        cn.hutool.core.net.url.UrlQuery.of(decoded,StandardCharsets.UTF_8).getQueryMap().forEach((key,value) -> query.put(key.toString(),value==null?null:value.toString()));
        return query;
    }
    private static String encode(String value) {
        return java.net.URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");
    }
    private static String text(Map<String,Object> args,String key) {
        if(!(args.get(key) instanceof String s)) throw new Failure("PREPARE_INPUT_INVALID"); return s;
    }
    private static long positiveInteger(Map<String,Object> args,String key) {
        try { long n=new java.math.BigDecimal(String.valueOf(args.get(key))).longValueExact(); if(n<=0) throw new ArithmeticException(); return n; }
        catch(Exception e) { throw new Failure("PREPARE_INPUT_INVALID"); }
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Map<String,Object> args,String key) {
        if(!(args.get(key) instanceof Map<?,?> m) || m.keySet().stream().anyMatch(k -> !(k instanceof String))) throw new Failure("PREPARE_INPUT_INVALID");
        return (Map<String,Object>)m;
    }
    public static String wireJson(Object value) {
        try { return JSON.writeValueAsString(value); } catch(Exception e) { throw new Failure("PREPARE_INPUT_INVALID"); }
    }
}
