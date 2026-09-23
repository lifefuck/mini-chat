package cc.lifefuck.minichat

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * 端到端加密管理器。
 *
 * - 每位用户在登录后生成一对 RSA 2048 密钥，私钥保存在 Android Keystore，公钥上传到服务器。
 * - 发送群消息时：随机生成 AES-256-GCM 对称密钥加密消息正文，
 *   再用每个接收者的 RSA 公钥分别加密该对称密钥。
 * - 服务器只存储密文 payload，无法读取内容。
 * - 接收方从 payload 中取出对应自己 userId 的加密对称密钥，
 *   用 Keystore 中的私钥解密，再 AES-GCM 解密正文。
 */
object CryptoManager {
    private const val KEY_ALIAS = "mini_chat_rsa_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    // RSA-OAEP SHA-256，可安全加密 256-bit（32 字节）AES 密钥
    private const val RSA_TRANSFORM = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
    private const val AES_TRANSFORM = "AES/GCM/NoPadding"
    private const val AES_KEY_SIZE = 32   // 256 bit
    private const val GCM_IV_SIZE = 12    // 96 bit
    private const val GCM_TAG_SIZE = 128  // bit

    /**
     * 强制重新生成本机 RSA 密钥对，确保私钥和当前上传的公钥严格匹配。
     *
     * 同一账号在换设备、重装、清数据或应用被恢复后，Android Keystore 中的旧私钥
     * 可能与服务端保存的公钥不再对应，导致无法解密。因此每次登录都重建密钥对，
     * 再把新公钥上传到服务器覆盖旧记录。
     *
     * @return 是否成功
     */
    fun ensureKeyPair(context: Context): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            // 先删除已有别名，确保新生成的公私钥配对
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
            val generator = java.security.KeyPairGenerator.getInstance("RSA", ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 导出本机公钥的 base64（X.509 SubjectPublicKeyInfo）。
     */
    fun getPublicKeyBase64(): String? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val cert = keyStore.getCertificate(KEY_ALIAS)
            cert?.publicKey?.encoded?.toBase64()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 加密一条群消息。
     *
     * @param plaintext 明文消息
     * @param recipientKeys 接收者 userId 到公钥 base64 的映射
     * @return 加密 payload JSON 字符串；失败返回 null
     */
    fun encryptPayload(plaintext: String, recipientKeys: Map<Int, String>): String? {
        return try {
            // 1. 随机生成 AES 密钥和 IV
            val aesKey = ByteArray(AES_KEY_SIZE).apply { SecureRandom().nextBytes(this) }
            val iv = ByteArray(GCM_IV_SIZE).apply { SecureRandom().nextBytes(this) }

            // 2. AES-GCM 加密明文
            val aesCipher = Cipher.getInstance(AES_TRANSFORM)
            aesCipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(GCM_TAG_SIZE, iv)
            )
            val cipherBytes = aesCipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

            // 3. 用每个接收者的 RSA 公钥加密 AES 密钥
            val keysObj = JSONObject()
            for ((userId, pubKeyBase64) in recipientKeys) {
                if (pubKeyBase64.isBlank()) continue
                val pubKeyBytes = pubKeyBase64.fromBase64() ?: continue
                val keyFactory = java.security.KeyFactory.getInstance("RSA")
                val pubKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(pubKeyBytes))
                val rsaCipher = Cipher.getInstance(RSA_TRANSFORM)
                rsaCipher.init(Cipher.ENCRYPT_MODE, pubKey)
                val encryptedAesKey = rsaCipher.doFinal(aesKey)
                keysObj.put(userId.toString(), encryptedAesKey.toBase64())
            }

            if (keysObj.length() == 0) {
                return null
            }

            JSONObject().apply {
                put("iv", iv.toBase64())
                put("cipher", cipherBytes.toBase64())
                put("keys", keysObj)
            }.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 解密一条群消息。
     *
     * @param payloadJson 服务器返回的 payload
     * @param myUserId 当前用户 id
     * @return 明文；失败返回 null
     */
    fun decryptPayload(payloadJson: String, myUserId: Int): String? {
        return try {
            val payload = JSONObject(payloadJson)
            val iv = payload.getString("iv").fromBase64() ?: return null
            val cipherBytes = payload.getString("cipher").fromBase64() ?: return null
            val encryptedAesKeyBase64 = payload.getJSONObject("keys").optString(myUserId.toString())
            if (encryptedAesKeyBase64.isBlank()) return null

            val encryptedAesKey = encryptedAesKeyBase64.fromBase64() ?: return null

            // 1. RSA 解密 AES 密钥
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey
            val rsaCipher = Cipher.getInstance(RSA_TRANSFORM)
            rsaCipher.init(Cipher.DECRYPT_MODE, privateKey)
            val aesKey = rsaCipher.doFinal(encryptedAesKey)

            // 2. AES-GCM 解密正文
            val aesCipher = Cipher.getInstance(AES_TRANSFORM)
            aesCipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(GCM_TAG_SIZE, iv)
            )
            val plain = aesCipher.doFinal(cipherBytes)
            String(plain, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
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
