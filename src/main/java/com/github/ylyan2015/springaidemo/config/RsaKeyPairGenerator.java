package com.github.ylyan2015.springaidemo.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA非对称加密工具类
 * 用于密码传输加密：前端用公钥加密，后端用私钥解密
 */
@Component
public class RsaKeyPairGenerator {

    private PublicKey publicKey;
    private PrivateKey privateKey;

    @PostConstruct
    public void init() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();
        this.publicKey = keyPair.getPublic();
        this.privateKey = keyPair.getPrivate();
    }

    /**
     * 获取Base64编码的公钥（供前端使用）
     */
    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * 用私钥解密前端传来的密文
     */
    public String decrypt(String encryptedBase64) throws Exception {
        byte[] encryptedBytes = Base64.getDecoder().decode(encryptedBase64);
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        byte[] decrypted = cipher.doFinal(encryptedBytes);
        return new String(decrypted, "UTF-8");
    }

    /**
     * 获取公钥对象
     */
    public PublicKey getPublicKey() {
        return publicKey;
    }
}
