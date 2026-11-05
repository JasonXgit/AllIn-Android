package org.thoughtcrime.securesms.googlemessages

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AllIn: the cryptography Google Messages for web uses (ported from mautrix-gmessages pkg/libgm/crypto).
 */
object GmCrypto {

  private val random = SecureRandom()

  fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

  /**
   * Requests to and from the phone: AES-256-CTR, then HMAC-SHA256 over ciphertext + IV.
   * Layout: ciphertext ‖ iv(16) ‖ hmac(32).
   */
  class AesCtrHmac(val aesKey: ByteArray, val hmacKey: ByteArray) {

    fun encrypt(plaintext: ByteArray): ByteArray {
      val iv = randomBytes(16)
      val cipher = Cipher.getInstance("AES/CTR/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
      val body = cipher.doFinal(plaintext) + iv
      return body + hmac(hmacKey, body)
    }

    fun decrypt(data: ByteArray): ByteArray {
      require(data.size >= 48) { "Encrypted data too short" }
      val body = data.copyOfRange(0, data.size - 32)
      val mac = data.copyOfRange(data.size - 32, data.size)
      require(MessageDigest.isEqual(mac, hmac(hmacKey, body))) { "HMAC mismatch" }
      val iv = body.copyOfRange(body.size - 16, body.size)
      val cipher = Cipher.getInstance("AES/CTR/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
      return cipher.doFinal(body, 0, body.size - 16)
    }
  }

  fun hmac(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
  }

  /** HKDF-SHA256 with a 32-byte output. */
  fun hkdf(key: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
    val prk = hmac(salt, key)
    return hmac(prk, info + byteArrayOf(1))
  }

  fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

  fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

  // P-256 keys

  fun generateP256(): KeyPair {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"), random)
    return generator.generateKeyPair()
  }

  fun privateKey(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

  fun publicKey(x509: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(x509))

  /** ECDSA over SHA-256 of [data], ASN.1/DER signature (Go's ecdsa.SignASN1 of sha256(data)). */
  fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray {
    val signature = Signature.getInstance("SHA256withECDSA")
    signature.initSign(privateKey)
    signature.update(data)
    return signature.sign()
  }

  /** The 32-byte big-endian X and Y of a P-256 public key. */
  fun coordinates(key: PublicKey): Pair<ByteArray, ByteArray> {
    val point = (key as ECPublicKey).w
    return fixed32(point.affineX) to fixed32(point.affineY)
  }

  fun publicKeyFromCoordinates(x: ByteArray, y: ByteArray, like: PublicKey): PublicKey {
    val params = (like as ECPublicKey).params
    val spec = ECPublicKeySpec(ECPoint(BigInteger(1, x), BigInteger(1, y)), params)
    return KeyFactory.getInstance("EC").generatePublic(spec)
  }

  fun ecdh(privateKey: PrivateKey, publicKey: PublicKey): ByteArray {
    val agreement = KeyAgreement.getInstance("ECDH")
    agreement.init(privateKey)
    agreement.doPhase(publicKey, true)
    return agreement.generateSecret()
  }

  private fun fixed32(value: BigInteger): ByteArray {
    val bytes = value.toByteArray()
    return when {
      bytes.size == 32 -> bytes
      bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
      else -> ByteArray(32 - bytes.size) + bytes
    }
  }

  // Media: chunked AES-256-GCM

  private const val OUTGOING_CHUNK = 1 shl 15

  private fun aad(index: Int, last: Boolean): ByteArray {
    return ByteBuffer.allocate(5).put(if (last) 1.toByte() else 0.toByte()).putInt(index).array()
  }

  fun encryptMedia(key: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(0)
    out.write(15) // log2(OUTGOING_CHUNK)
    val plainChunk = OUTGOING_CHUNK - 12 - 16
    var index = 0
    var offset = 0
    while (offset < data.size) {
      val size = minOf(plainChunk, data.size - offset)
      val last = offset + size >= data.size
      val nonce = randomBytes(12)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
      cipher.updateAAD(aad(index, last))
      out.write(nonce)
      out.write(cipher.doFinal(data, offset, size))
      offset += size
      index++
    }
    return out.toByteArray()
  }

  fun decryptMedia(key: ByteArray, encrypted: ByteArray): ByteArray {
    if (encrypted.isEmpty()) return encrypted
    require(encrypted[0].toInt() == 0) { "Bad media header" }
    val chunkSize = 1 shl encrypted[1].toInt()
    val out = ByteArrayOutputStream()
    var index = 0
    var offset = 2
    while (offset < encrypted.size) {
      val size = minOf(chunkSize, encrypted.size - offset)
      val last = offset + size >= encrypted.size
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, encrypted, offset, 12))
      cipher.updateAAD(aad(index, last))
      out.write(cipher.doFinal(encrypted, offset + 12, size - 12))
      offset += size
      index++
    }
    return out.toByteArray()
  }
}
