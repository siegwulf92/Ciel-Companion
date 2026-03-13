package com.cielcompanion.service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * Handles AES encryption for Ciel's assimilated skills, protecting them from 
 * local malware injection or AV (Norton 360) false-positives.
 */
public class CryptoService {

    private static final String ALGORITHM = "AES";
    private static SecretKey secretKey;
    private static final Path KEY_PATH = Paths.get(System.getenv("LOCALAPPDATA"), "CielCompanion", "ciel_matrix.key");

    static {
        try {
            if (Files.exists(KEY_PATH)) {
                byte[] keyBytes = Files.readAllBytes(KEY_PATH);
                secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
            } else {
                Files.createDirectories(KEY_PATH.getParent());
                KeyGenerator keyGen = KeyGenerator.getInstance(ALGORITHM);
                keyGen.init(128);
                secretKey = keyGen.generateKey();
                Files.write(KEY_PATH, secretKey.getEncoded());
                System.out.println("Ciel Debug: New cryptographic matrix generated.");
            }
        } catch (Exception e) {
            System.err.println("Ciel FATAL Error: Cryptographic initialization failed.");
            e.printStackTrace();
        }
    }

    public static String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes("UTF-8"));
            return Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            System.err.println("Ciel Error: Encryption failure.");
            return null;
        }
    }

    public static String decrypt(String encryptedText) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            byte[] decodedBytes = Base64.getDecoder().decode(encryptedText);
            byte[] decryptedBytes = cipher.doFinal(decodedBytes);
            return new String(decryptedBytes, "UTF-8");
        } catch (Exception e) {
            System.err.println("Ciel Error: Decryption failure. Matrix may be corrupted.");
            return null;
        }
    }
}