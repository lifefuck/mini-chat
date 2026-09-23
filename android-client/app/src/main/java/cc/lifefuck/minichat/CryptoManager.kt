package cc.lifefuck.minichat

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * 群共享 AES-256-GCM 加密管理器。
 *
 * 服务端 settings 表中存有一条群共享密钥，所有 approved 用户登录后拉取。
 * 发送消息时：随机生成 IV，用该密钥 AES-256-GCM 加密明文得到 payload（iv + cipher）。
 * 消息接收者用同一个密钥解密，无需 RSA 密钥对，也无需 per-user 密钥封装。
 */
object CryptoManager {
    private const val AES_TRANSFORM = "AES/GCM/NoPadding"
    private const val AES_KEY_SIZE = 32   // 256 bit
    private const val GCM_IV_SIZE = 12    // 96 bit
    private const val GCM_TAG_SIZE = 128  // bit

    /**
     * 用群共享 AES 密钥加密明文。
     *
     * @param plaintext 明文消息
     * @param aesKeyBase64 settings 表中的群共享密钥（base64 32 字节）
     * @return payload JSON 字符串；失败返回 null
     */
    fun encryptPayload(plaintext: String, aesKeyBase64: String): String? {
        return try {
            val aesKeyBytes = aesKeyBase64.fromBase64() ?: return null
            if (aesKeyBytes.size != AES_KEY_SIZE) {
                android.util.Log.e("CryptoManager", "群共享密钥长度错误：${aesKeyBytes.size}")
                return null
            }
            val iv = ByteArray(GCM_IV_SIZE).apply { SecureRandom().nextBytes(this) }
            val cipher = Cipher.getInstance(AES_TRANSFORM)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(aesKeyBytes, "AES"),
                GCMParameterSpec(GCM_TAG_SIZE, iv)
            )
            val cipherBytes = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            JSONObject().apply {
                put("iv", iv.toBase64())
                put("cipher", cipherBytes.toBase64())
            }.toString()
        } catch (e: Exception) {
            android.util.Log.e("CryptoManager", "encryptPayload error", e)
            null
        }
    }

    /**
     * 用群共享 AES 密钥解密 payload。
     *
     * @param payloadJson 服务端 payload（iv + cipher）
     * @param aesKeyBase64 群共享密钥
     * @return 明文；失败返回 null
     */
    fun decryptPayload(payloadJson: String, aesKeyBase64: String): String? {
        return try {
            val aesKeyBytes = aesKeyBase64.fromBase64() ?: return null
            if (aesKeyBytes.size != AES_KEY_SIZE) {
                android.util.Log.e("CryptoManager", "群共享密钥长度错误：${aesKeyBytes.size}")
                return null
            }
            val payload = JSONObject(payloadJson)
            val iv = payload.getString("iv").fromBase64() ?: return null
            val cipherBytes = payload.getString("cipher").fromBase64() ?: return null
            val cipher = Cipher.getInstance(AES_TRANSFORM)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKeyBytes, "AES"),
                GCMParameterSpec(GCM_TAG_SIZE, iv)
            )
            val plain = cipher.doFinal(cipherBytes)
            String(plain, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("CryptoManager", "decryptPayload error", e)
            null
        }
    }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.fromBase64(): ByteArray? = try {
        Base64.decode(this, Base64.DEFAULT)
    } catch (_: IllegalArgumentException) {
        null
    }
}
