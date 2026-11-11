package io.yuan.octopus.infra.common;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CredentialEncryptorTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @Test
    void roundTripUsesV1Format() {
        CredentialEncryptor enc = new CredentialEncryptor(KEY);
        String cipher = enc.encrypt("s3cret-token");
        assertTrue(cipher.startsWith(CredentialEncryptor.PREFIX_V1));
        assertTrue(CredentialEncryptor.isEncrypted(cipher));
        assertEquals("s3cret-token", enc.decrypt(cipher));
    }

    @Test
    void randomIvMakesCiphertextsDiffer() {
        CredentialEncryptor enc = new CredentialEncryptor(KEY);
        assertNotEquals(enc.encrypt("same"), enc.encrypt("same"));
    }

    @Test
    void decryptsLegacyFormatWrittenByOlderCodeIntelligence() throws Exception {
        // 旧格式：base64(iv ‖ ciphertext+tag)，与重构前的实现逐字节一致
        byte[] iv = new byte[12];
        for (int i = 0; i < iv.length; i++) iv[i] = (byte) i;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal("legacy-pass".getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ct, 0, combined, iv.length, ct.length);
        String legacy = Base64.getEncoder().encodeToString(combined);

        assertFalse(CredentialEncryptor.isEncrypted(legacy));
        assertEquals("legacy-pass", new CredentialEncryptor(KEY).decrypt(legacy));
    }

    @Test
    void decryptsV1FormatWrittenByOlderReviewAgent() throws Exception {
        // 旧 review-agent SecretCipher：enc:v1:<b64 iv>:<b64 ct>，32 字节密钥时直接使用原始字节
        byte[] iv = new byte[12];
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal("sk-model".getBytes(StandardCharsets.UTF_8));
        String old = "enc:v1:" + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ct);
        assertEquals("sk-model", new CredentialEncryptor(KEY).decrypt(old));
    }

    @Test
    void wrongKeyFails() {
        String cipher = new CredentialEncryptor(KEY).encrypt("x");
        CredentialEncryptor other = new CredentialEncryptor("fedcba9876543210fedcba9876543210");
        assertThrows(RuntimeException.class, () -> other.decrypt(cipher));
    }

    @Test
    void rejectsMissingOrInvalidKeyLength() {
        assertThrows(IllegalArgumentException.class, () -> new CredentialEncryptor(null));
        assertThrows(IllegalArgumentException.class, () -> new CredentialEncryptor(""));
        assertThrows(IllegalArgumentException.class, () -> new CredentialEncryptor("short"));
        assertDoesNotThrow(() -> new CredentialEncryptor("0123456789abcdef"));
        assertDoesNotThrow(() -> new CredentialEncryptor("0123456789abcdef01234567"));
    }

    @Test
    void maskExposesAtMostFourChars() {
        assertEquals("", CredentialEncryptor.mask(null));
        assertEquals("", CredentialEncryptor.mask(""));
        assertEquals("********", CredentialEncryptor.mask("sk-short"));
        assertEquals("sk-a****", CredentialEncryptor.mask("sk-abcdefghijklmnop"));
    }
}
