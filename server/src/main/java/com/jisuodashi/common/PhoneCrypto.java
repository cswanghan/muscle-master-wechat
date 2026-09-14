package com.jisuodashi.common;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * AES-256-GCM at rest; HMAC-SHA256(e164, pepper) for lookup. Cipher bytes never leave this class
 * toward C-end DTOs.
 */
@Component
public class PhoneCrypto {

    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String CN_PREFIX = "+86";

    private final byte[] dek;
    private final byte[] pepper;
    private final SecureRandom random = new SecureRandom();

    public PhoneCrypto(AppProperties properties) {
        this.pepper = properties.getCrypto().getPhonePepper().getBytes(StandardCharsets.UTF_8);
        String dekB64 = properties.getCrypto().getDekBase64();
        if (dekB64 == null || dekB64.isBlank()) {
            // Dev convenience: never persist this derived key in production config.
            this.dek = sha256(properties.getCrypto().getPhonePepper());
        } else {
            byte[] decoded = Base64.getDecoder().decode(dekB64);
            if (decoded.length != 32) {
                throw new IllegalStateException("app.crypto.dek-base64 must decode to 32 bytes");
            }
            this.dek = decoded;
        }
    }

    public static String normalizeCnMobile(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.trim().replaceAll("\\s+", "");
        if (digits.startsWith("+86")) {
            digits = digits.substring(3);
        } else if (digits.startsWith("86") && digits.length() == 13) {
            digits = digits.substring(2);
        }
        if (!digits.matches("1\\d{10}")) {
            throw new ApiException(ErrorCodes.BAD_REQUEST, "手机号须为 11 位");
        }
        return CN_PREFIX + digits;
    }

    public byte[] encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] packed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + packed.length).put(iv).put(packed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    public String decrypt(byte[] cipherBytes) {
        if (cipherBytes == null || cipherBytes.length == 0) {
            return null;
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(cipherBytes);
            byte[] iv = new byte[GCM_IV_LEN];
            buf.get(iv);
            byte[] packed = new byte[buf.remaining()];
            buf.get(packed);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(packed), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decrypt failed", e);
        }
    }

    public String hashE164(String e164) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(e164.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("hmac failed", e);
        }
    }

    public PhoneParts sealMobile(String rawPhone) {
        String e164 = normalizeCnMobile(rawPhone);
        return new PhoneParts(e164, hashE164(e164), encrypt(e164));
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public record PhoneParts(String e164, String hash, byte[] cipher) {
    }

    /**
     * 展示用打码：{@code 186****1111}。位数不够就原样返回，不硬凑。
     *
     * <p>放在这里而不是各包自己写一份：前台和会员端都要用，两份实现迟早漂移成
     * 同一个号在两个页面上显示不一样。
     */
    public static String mask(String raw) {
        String digits = maskDigits(raw);
        if (digits.length() < 7) {
            return digits.isEmpty() ? "****" : digits;
        }
        return digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }

    /**
     * 取出用于展示的号码位。手机号在库里是 E164（{@code +8618600001111}），
     * 打码要显示的是用户认得的 11 位，所以国内号要把 86 去掉 ——
     * 不去的话会显示成 {@code 861****1111}，看着就不像自己的号。
     */
    private static String maskDigits(String raw) {
        if (raw == null) {
            return "";
        }
        String d = raw.replaceAll("\\D", "");
        if (d.startsWith("86") && d.length() == 13) {
            return d.substring(2);
        }
        return d;
    }
}
