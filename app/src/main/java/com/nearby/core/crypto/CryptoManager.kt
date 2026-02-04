package com.nearby.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CryptoManager @Inject constructor() {

    companion object {
        private const val KEY_ALGORITHM = "EC"
        private const val KEY_SIZE = 256
        private const val KEY_AGREEMENT_ALGORITHM = "ECDH"
        private const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val EMOJI_FINGERPRINT_LENGTH = 6

        private val FINGERPRINT_EMOJIS = listOf(
            "\uD83D\uDE00", "\uD83D\uDE03", "\uD83D\uDE04", "\uD83D\uDE01", "\uD83D\uDE06",
            "\uD83D\uDE05", "\uD83E\uDD23", "\uD83D\uDE02", "\uD83D\uDE42", "\uD83D\uDE43",
            "\uD83D\uDE09", "\uD83D\uDE0A", "\uD83D\uDE07", "\uD83E\uDD70", "\uD83D\uDE0D",
            "\uD83E\uDD29", "\uD83D\uDE18", "\uD83D\uDE17", "\uD83D\uDE1A", "\uD83D\uDE19",
            "\uD83D\uDE0B", "\uD83D\uDE1B", "\uD83D\uDE1C", "\uD83E\uDD2A", "\uD83D\uDE1D",
            "\uD83E\uDD11", "\uD83E\uDD17", "\uD83E\uDD2D", "\uD83E\uDD2B", "\uD83E\uDD14",
            "\uD83E\uDD10", "\uD83E\uDD28", "\uD83D\uDE10", "\uD83D\uDE11", "\uD83D\uDE36",
            "\uD83D\uDE0F", "\uD83D\uDE12", "\uD83D\uDE44", "\uD83D\uDE2C", "\uD83E\uDD25"
        )
    }

    fun generateKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        keyPairGenerator.initialize(KEY_SIZE, SecureRandom())
        return keyPairGenerator.generateKeyPair()
    }

    fun deriveSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM)
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(sharedSecret)
    }

    fun encrypt(plaintext: ByteArray, sharedSecret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val secretKey = SecretKeySpec(sharedSecret, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        val ciphertext = cipher.doFinal(plaintext)

        return iv + ciphertext
    }

    fun decrypt(ciphertext: ByteArray, sharedSecret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_ALGORITHM)
        val iv = ciphertext.copyOfRange(0, GCM_IV_LENGTH)
        val encryptedData = ciphertext.copyOfRange(GCM_IV_LENGTH, ciphertext.size)
        val secretKey = SecretKeySpec(sharedSecret, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)

        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        return cipher.doFinal(encryptedData)
    }

    fun encodePublicKey(publicKey: PublicKey): ByteArray {
        return publicKey.encoded
    }

    fun decodePublicKey(encoded: ByteArray): PublicKey {
        val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)
        val keySpec = X509EncodedKeySpec(encoded)
        return keyFactory.generatePublic(keySpec)
    }

    fun decodePrivateKey(encoded: ByteArray): PrivateKey {
        val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)
        val keySpec = PKCS8EncodedKeySpec(encoded)
        return keyFactory.generatePrivate(keySpec)
    }

    fun publicKeyToBase64(publicKey: PublicKey): String {
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    fun base64ToPublicKey(base64: String): PublicKey {
        val decoded = Base64.decode(base64, Base64.NO_WRAP)
        return decodePublicKey(decoded)
    }

    fun generateFingerprint(publicKey1: PublicKey, publicKey2: PublicKey): String {
        val combined = publicKey1.encoded + publicKey2.encoded
        val sorted = if (publicKey1.encoded.contentHashCode() < publicKey2.encoded.contentHashCode()) {
            publicKey1.encoded + publicKey2.encoded
        } else {
            publicKey2.encoded + publicKey1.encoded
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(sorted)

        return hash.take(EMOJI_FINGERPRINT_LENGTH)
            .map { byte -> FINGERPRINT_EMOJIS[(byte.toInt() and 0xFF) % FINGERPRINT_EMOJIS.size] }
            .joinToString(" ")
    }

    fun generateTextFingerprint(publicKey: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKey.encoded)
        return hash.take(8)
            .joinToString(":") { "%02X".format(it) }
    }
}
