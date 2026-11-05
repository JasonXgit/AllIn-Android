package org.thoughtcrime.securesms.googlemessages

import android.app.Application
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.thoughtcrime.securesms.googlemessages.proto.ActionType
import org.thoughtcrime.securesms.googlemessages.proto.BugleRoute
import org.thoughtcrime.securesms.googlemessages.proto.Device
import org.thoughtcrime.securesms.googlemessages.proto.EmptyArr
import org.thoughtcrime.securesms.googlemessages.proto.LongPollingPayload
import org.thoughtcrime.securesms.googlemessages.proto.MessageType
import org.thoughtcrime.securesms.googlemessages.proto.OutgoingRPCMessage
import org.thoughtcrime.securesms.googlemessages.proto.SignInGaiaResponse
import java.security.Signature

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class)
class GoogleMessagesProtocolTest {

  @Test
  fun hkdf_matchesRfc5869() {
    val ikm = ByteArray(22) { 0x0b }
    val salt = "000102030405060708090a0b0c".decodeHex().toByteArray()
    val info = "f0f1f2f3f4f5f6f7f8f9".decodeHex().toByteArray()
    val expected = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf".decodeHex().toByteArray()
    assertArrayEquals(expected, GmCrypto.hkdf(ikm, salt, info))
  }

  @Test
  fun aesCtrHmac_roundTripsAndRejectsTampering() {
    val crypto = GmCrypto.AesCtrHmac(GmCrypto.randomBytes(32), GmCrypto.randomBytes(32))
    val plain = "hello from the phone".toByteArray()
    val encrypted = crypto.encrypt(plain)
    assertEquals(plain.size + 48, encrypted.size)
    assertArrayEquals(plain, crypto.decrypt(encrypted))

    encrypted[0] = (encrypted[0] + 1).toByte()
    try {
      crypto.decrypt(encrypted)
      fail("Tampered data was accepted")
    } catch (e: IllegalArgumentException) {
      // expected
    }
  }

  @Test
  fun media_roundTripsAcrossChunks() {
    val key = GmCrypto.randomBytes(32)
    val data = GmCrypto.randomBytes(100_000)
    val encrypted = GmCrypto.encryptMedia(key, data)
    assertEquals(0, encrypted[0].toInt())
    assertEquals(15, encrypted[1].toInt())
    assertArrayEquals(data, GmCrypto.decryptMedia(key, encrypted))
  }

  @Test
  fun signature_verifies() {
    val keys = GmCrypto.generateP256()
    val sig = GmCrypto.sign(GmCrypto.privateKey(keys.private.encoded), "id:123".toByteArray())
    val verify = Signature.getInstance("SHA256withECDSA")
    verify.initVerify(GmCrypto.publicKey(keys.public.encoded))
    verify.update("id:123".toByteArray())
    assertTrue(verify.verify(sig))
  }

  @Test
  fun pblite_encodesLikeLibgm() {
    val message = OutgoingRPCMessage(
      mobile = Device(userID = 5, sourceID = "abc", network = "Bugle"),
      data_ = OutgoingRPCMessage.Data(
        requestID = "r1",
        bugleRoute = BugleRoute.DataEvent,
        messageData = byteArrayOf(1, 2, 3).toByteString(),
        messageTypeData = OutgoingRPCMessage.Data.Type(emptyArr = EmptyArr(), messageType = MessageType.BUGLE_MESSAGE)
      ),
      TTL = 0,
      destRegistrationIDs = listOf("dest")
    )
    val json = JSONArray(Pblite.encode(message))

    // Field n sits at index n - 1; unset fields are null; the array runs to the highest field number.
    assertEquals(9, json.length())
    assertEquals(JSONArray("[5,\"abc\",\"Bugle\"]").toString(), json.getJSONArray(0).toString())
    val data = json.getJSONArray(1)
    assertEquals("r1", data.getString(0))
    assertEquals(19, data.getInt(1))
    assertEquals("AQID", data.getString(11))
    assertEquals("[[],2]", data.getJSONArray(22).toString())
    assertTrue(json.isNull(2))
    assertTrue(json.isNull(4)) // TTL 0 is left out
    // destRegistrationIDs is a "binary" field: base64 of the string.
    assertEquals("ZGVzdA==", json.getJSONArray(8).getString(0))

    assertEquals(message, Pblite.decode(json, OutgoingRPCMessage::class.java))
  }

  @Test
  fun pblite_decodesStringNumbersAndTrailingObject() {
    // int64s may come as strings, and fields past the array may sit in a trailing {"n": value} object.
    val payload = Pblite.decode("[null,null,null,[\"7\"]]", LongPollingPayload::class.java)
    assertEquals(7, payload.ack?.count)

    val response = Pblite.decode("[null,\"YnJvd3Nlcg==\",{\"4\":[\"AQID\",\"86400000000\"]}]", SignInGaiaResponse::class.java)
    assertEquals("browser", response.maybeBrowserUUID)
    assertEquals(86_400_000_000L, response.tokenData?.TTL)
    assertArrayEquals(byteArrayOf(1, 2, 3), response.tokenData?.tachyonAuthToken?.toByteArray())
  }

  @Test
  fun emoji_listsMatchLibgmSizes() {
    assertTrue(GmEmoji.V0.size > 300)
    assertEquals(GmEmoji.V0.distinct().size + 14 - 10, GmEmoji.V1.size)
    assertTrue(ActionType.CREATE_GAIA_PAIRING_CLIENT_INIT.value == 44)
  }
}
