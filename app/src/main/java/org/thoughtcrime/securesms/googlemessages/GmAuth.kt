package org.thoughtcrime.securesms.googlemessages

import android.util.Base64
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.thoughtcrime.securesms.googlemessages.proto.Device
import java.util.UUID

/**
 * AllIn: everything needed to talk to Google Messages after pairing (libgm's AuthData). Saved as JSON in
 * SignalStore (the app's encrypted database), never in a backup.
 */
class GmAuth(
  /** Keys that encrypt requests to and from the phone. Random until pairing replaces them. */
  var aesKey: ByteArray = GmCrypto.randomBytes(32),
  var hmacKey: ByteArray = GmCrypto.randomBytes(32),
  /** P-256 key that signs token refreshes (PKCS#8 / X.509). */
  val refreshPrivate: ByteArray,
  val refreshPublic: ByteArray,
  var browser: Device? = null,
  var mobile: Device? = null,
  var tachyonToken: ByteArray? = null,
  var tachyonExpiryMs: Long = 0,
  var tachyonTtlMicros: Long = 0,
  /** The browser's device ID from /web/config. */
  var sessionId: UUID? = null,
  /** The phone's registration ID (Google account pairing). */
  var destRegId: UUID? = null,
  var destPhoneId: Long = 0,
  var pairingId: UUID? = null
) {

  val crypto: GmCrypto.AesCtrHmac
    get() = GmCrypto.AesCtrHmac(aesKey, hmacKey)

  val isPaired: Boolean
    get() = browser != null && tachyonToken != null && pairingId != null

  fun toJson(): String {
    return JSONObject().apply {
      put("aes", b64(aesKey))
      put("hmac", b64(hmacKey))
      put("refreshPrivate", b64(refreshPrivate))
      put("refreshPublic", b64(refreshPublic))
      browser?.let { put("browser", b64(it.encode())) }
      mobile?.let { put("mobile", b64(it.encode())) }
      tachyonToken?.let { put("token", b64(it)) }
      put("expiry", tachyonExpiryMs)
      put("ttl", tachyonTtlMicros)
      sessionId?.let { put("session", it.toString()) }
      destRegId?.let { put("destReg", it.toString()) }
      put("destPhone", destPhoneId)
      pairingId?.let { put("pairing", it.toString()) }
    }.toString()
  }

  companion object {
    fun create(): GmAuth {
      val keys = GmCrypto.generateP256()
      return GmAuth(refreshPrivate = keys.private.encoded, refreshPublic = keys.public.encoded)
    }

    fun fromJson(json: String): GmAuth {
      val o = JSONObject(json)
      return GmAuth(
        aesKey = unb64(o.getString("aes")),
        hmacKey = unb64(o.getString("hmac")),
        refreshPrivate = unb64(o.getString("refreshPrivate")),
        refreshPublic = unb64(o.getString("refreshPublic")),
        browser = o.optString("browser").takeIf { it.isNotEmpty() }?.let { Device.ADAPTER.decode(unb64(it)) },
        mobile = o.optString("mobile").takeIf { it.isNotEmpty() }?.let { Device.ADAPTER.decode(unb64(it)) },
        tachyonToken = o.optString("token").takeIf { it.isNotEmpty() }?.let { unb64(it) },
        tachyonExpiryMs = o.optLong("expiry"),
        tachyonTtlMicros = o.optLong("ttl"),
        sessionId = o.optString("session").takeIf { it.isNotEmpty() }?.let(UUID::fromString),
        destRegId = o.optString("destReg").takeIf { it.isNotEmpty() }?.let(UUID::fromString),
        destPhoneId = o.optLong("destPhone"),
        pairingId = o.optString("pairing").takeIf { it.isNotEmpty() }?.let(UUID::fromString)
      )
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unb64(text: String) = Base64.decode(text, Base64.DEFAULT)
  }
}

internal fun ByteArray.bs() = toByteString()
