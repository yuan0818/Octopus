package io.yuan.octopus.preparation;
import cn.hutool.crypto.asymmetric.RSA;
import cn.hutool.crypto.asymmetric.KeyType;
import cn.hutool.crypto.symmetric.AES;
import cn.hutool.crypto.Mode;
import cn.hutool.crypto.Padding;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyPairGenerator;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparationRuntimeTest {
    @Test void platformHutoolCanDecryptCipherAndDataAndEachCallHasANewAesKey() throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var keys=generator.generateKeyPair();
        var args=new LinkedHashMap<String,Object>();
        var content=Map.of("email","fixture@example.invalid","password","fixture-only 中文\\\"${notAnExpression}");
        args.put("content",content);args.put("publicKey",Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        args.put("keyPairId","fixture");args.put("timestamp",123456789L);args.put("version",1);
        var first=(Map<?,?>)PreparationRuntime.execute("platform.encrypt","1",args).get("body");
        var second=(Map<?,?>)PreparationRuntime.execute("platform.encrypt","1",args).get("body");
        var rsa=new RSA(keys.getPrivate(),null);
        String aesKey=rsa.decryptStr((String)first.get("cipher"),KeyType.PrivateKey);
        var aes=new AES(Mode.ECB,Padding.PKCS5Padding,aesKey.getBytes(StandardCharsets.UTF_8));
        assertEquals(content,new ObjectMapper().readValue(aes.decryptStr((String)first.get("data")),Map.class));
        assertFalse(first.containsKey("content")); assertNotEquals(first.get("data"),second.get("data"));
        assertNotEquals(aesKey,rsa.decryptStr((String)second.get("cipher"),KeyType.PrivateKey));
    }
    @Test void digestIsAHashAndDoesNotEvaluateFunctionLookingInput() {
        assertEquals("c49f00b92667a35c63708933384dad52",PreparationRuntime.execute("digest","1",Map.of("algorithm","MD5","text","Errare humanum est")).get("value"));
        assertEquals("${__groovy(throw new Exception())}",PreparationRuntime.execute("string.concat","1",Map.of("prefix","","value","${__groovy(throw new Exception())}")).get("value"));
        assertThrows(PreparationRuntime.Failure.class,()->PreparationRuntime.execute("digest","1",Map.of("algorithm","HMAC-MD5","text","fixture")));
    }
}
