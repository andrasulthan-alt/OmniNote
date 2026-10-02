package io.github.andrasulthan.omninote;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Vault encryption for OmniNote: Argon2id turns the vault password into a 256-bit key,
 * AES-256-GCM encrypts the note. Wrong passwords and changed files are detected.
 * Text format: "OMNIVAULT1:" + Base64(12-byte nonce + ciphertext + 16-byte tag).
 */
public final class VaultCrypto {

    private VaultCrypto() {}

    public static final String PREFIX = "OMNIVAULT1:";
    private static final String CHECK_TEXT = "omninote-vault-check";
    private static final int ITERATIONS = 3;
    private static final int MEMORY_KIB = 32768;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    public static byte[] newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return salt;
    }

    public static byte[] deriveKey(String password, byte[] salt) {
        return Argon2.hash(password.getBytes(StandardCharsets.UTF_8), salt, ITERATIONS, MEMORY_KIB, 1, 32);
    }

    public static String encrypt(byte[] key, String plain) throws Exception {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[nonce.length + sealed.length];
        System.arraycopy(nonce, 0, all, 0, nonce.length);
        System.arraycopy(sealed, 0, all, nonce.length, sealed.length);
        return PREFIX + Base64.getEncoder().encodeToString(all);
    }

    /** Returns the plain text, or throws when the key is wrong or the text was changed. */
    public static String decrypt(byte[] key, String armored) throws Exception {
        String body = armored.trim();
        if (!body.startsWith(PREFIX)) throw new IllegalArgumentException("Not a vault text");
        byte[] all = Base64.getDecoder().decode(body.substring(PREFIX.length()));
        if (all.length < NONCE_BYTES + 16) throw new IllegalArgumentException("Too short");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
            new GCMParameterSpec(TAG_BITS, all, 0, NONCE_BYTES));
        byte[] plain = cipher.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES);
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** A small encrypted marker used to check the password without touching any note. */
    public static String makeCheck(byte[] key) throws Exception {
        return encrypt(key, CHECK_TEXT);
    }

    public static boolean verify(byte[] key, String check) {
        try {
            return CHECK_TEXT.equals(decrypt(key, check));
        } catch (Exception e) {
            return false;
        }
    }
}
