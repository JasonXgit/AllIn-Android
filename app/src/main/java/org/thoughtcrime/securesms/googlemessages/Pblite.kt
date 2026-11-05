package org.thoughtcrime.securesms.googlemessages

import android.util.Base64
import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.WireEnum
import com.squareup.wire.WireField
import okio.Buffer
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

/**
 * AllIn: "pblite", the JSON form of protobuf that Google Messages for web uses: a message is a JSON array where
 * index i holds field i + 1, nested messages are nested arrays and bytes are base64. Ported from go.mau.fi/util/pblite.
 *
 * Works on any Wire message by reading its @WireField annotations. Decoding writes binary protobuf and hands it to
 * the class's own ADAPTER, so Wire does the object building.
 */
object Pblite {

  /** Fields marked (pblite.pblite_binary) in mautrix-gmessages: sent as base64 of their binary form. Class name to tags. */
  private val BINARY_FIELDS: Map<String, Set<Int>> = mapOf(
    "$PROTO.SignInGaiaRequest\$Inner" to setOf(36),
    "$PROTO.SignInGaiaResponse" to setOf(2),
    "$PROTO.RPCGaiaData\$UnknownContainer\$Item2\$Item1" to setOf(1),
    "$PROTO.RPCGaiaData\$UnknownContainer\$Item4" to setOf(1, 8),
    "$PROTO.OutgoingRPCMessage" to setOf(9)
  )

  private class FieldInfo(val field: Field, val tag: Int, val adapter: String, val label: WireField.Label) {
    val repeated = label == WireField.Label.REPEATED || label == WireField.Label.PACKED
  }

  private val fieldCache = ConcurrentHashMap<Class<*>, List<FieldInfo>>()

  private fun fields(cls: Class<*>): List<FieldInfo> = fieldCache.getOrPut(cls) {
    cls.declaredFields.mapNotNull { field ->
      val annotation = field.getAnnotation(WireField::class.java) ?: return@mapNotNull null
      field.isAccessible = true
      FieldInfo(field, annotation.tag, annotation.adapter, annotation.label)
    }
  }

  private fun isBinary(cls: Class<*>, tag: Int) = BINARY_FIELDS[cls.name]?.contains(tag) == true

  private fun adapterClass(adapter: String): Class<*>? {
    if (adapter.startsWith("com.squareup.wire.ProtoAdapter#")) return null
    return Class.forName(adapter.substringBefore('#'))
  }

  @Suppress("UNCHECKED_CAST")
  private fun <M> adapterOf(cls: Class<M>): ProtoAdapter<M> = cls.getField("ADAPTER").get(null) as ProtoAdapter<M>

  // Encoding

  fun encode(message: Message<*, *>): String = toArray(message).toString()

  fun toArray(message: Message<*, *>): JSONArray {
    val infos = fields(message.javaClass)
    val size = infos.maxOfOrNull { it.tag } ?: 0
    val out = JSONArray()
    for (i in 0 until size) out.put(JSONObject.NULL)
    for (info in infos) {
      val value = info.field.get(message) ?: continue
      if (info.repeated) {
        val list = value as List<*>
        if (list.isEmpty()) continue
        val array = JSONArray()
        list.forEach { array.put(encodeOne(message.javaClass, info, it!!)) }
        out.put(info.tag - 1, array)
      } else {
        if (info.label == WireField.Label.OMIT_IDENTITY && isIdentity(value)) continue
        out.put(info.tag - 1, encodeOne(message.javaClass, info, value))
      }
    }
    return out
  }

  private fun isIdentity(value: Any): Boolean = when (value) {
    is String -> value.isEmpty()
    is ByteString -> value.size == 0
    is Boolean -> !value
    is Int -> value == 0
    is Long -> value == 0L
    is Float -> value == 0f
    is Double -> value == 0.0
    is WireEnum -> value.value == 0
    else -> false
  }

  private fun encodeOne(owner: Class<*>, info: FieldInfo, value: Any): Any {
    val binary = isBinary(owner, info.tag)
    return when (value) {
      is Message<*, *> -> if (binary) base64(adapterOf(value.javaClass).encode(value)) else toArray(value)
      is ByteString -> base64(value.toByteArray())
      is String -> if (binary) base64(value.toByteArray()) else value
      is WireEnum -> value.value
      is Int -> value.toLong()
      else -> value
    }
  }

  // Decoding

  fun <M> decode(json: String, cls: Class<M>): M = decode(JSONArray(json), cls)

  fun <M> decode(array: JSONArray, cls: Class<M>): M = adapterOf(cls).decode(toBinary(array, cls))

  private fun toBinary(input: JSONArray, cls: Class<*>): ByteArray {
    var array = input
    var extras: JSONObject? = null
    if (array.length() > 0 && array.opt(array.length() - 1) is JSONObject) {
      extras = array.getJSONObject(array.length() - 1)
      array = JSONArray().also { copy -> for (i in 0 until input.length() - 1) copy.put(input.opt(i)) }
    }
    val out = Buffer()
    for (info in fields(cls)) {
      var value: Any? = extras?.opt(info.tag.toString())
      if (value == null || value == JSONObject.NULL) value = array.opt(info.tag - 1)
      if (value == null || value == JSONObject.NULL) continue
      if (info.repeated) {
        val list = value as? JSONArray ?: continue
        for (i in 0 until list.length()) {
          val element = list.opt(i)
          if (element != null && element != JSONObject.NULL) writeOne(out, cls, info, element)
        }
      } else {
        writeOne(out, cls, info, value)
      }
    }
    return out.readByteArray()
  }

  private fun writeOne(out: Buffer, owner: Class<*>, info: FieldInfo, value: Any) {
    val binary = isBinary(owner, info.tag)
    val type = adapterClass(info.adapter)
    when {
      type != null && WireEnum::class.java.isAssignableFrom(type) -> writeVarint(out, info.tag, number(value))
      type != null -> {
        val bytes = if (binary) unbase64(value as String) else toBinary(value as JSONArray, type)
        writeBytes(out, info.tag, bytes)
      }
      else -> when (info.adapter.substringAfter('#')) {
        "STRING" -> writeBytes(out, info.tag, if (binary) unbase64(value as String) else (value as String).toByteArray())
        "BYTES" -> writeBytes(out, info.tag, unbase64(value as String))
        "BOOL" -> writeVarint(out, info.tag, if (value is Boolean) (if (value) 1 else 0) else if (number(value) != 0L) 1 else 0)
        "INT32", "INT64", "UINT32", "UINT64" -> writeVarint(out, info.tag, number(value))
        "SINT32", "SINT64" -> number(value).let { writeVarint(out, info.tag, (it shl 1) xor (it shr 63)) }
        "FIXED32", "SFIXED32" -> { writeKey(out, info.tag, 5); out.writeIntLe(number(value).toInt()) }
        "FIXED64", "SFIXED64" -> { writeKey(out, info.tag, 1); out.writeLongLe(number(value)) }
        "FLOAT" -> { writeKey(out, info.tag, 5); out.writeIntLe(java.lang.Float.floatToIntBits((value as Number).toFloat())) }
        "DOUBLE" -> { writeKey(out, info.tag, 1); out.writeLongLe(java.lang.Double.doubleToLongBits((value as Number).toDouble())) }
        else -> throw IllegalArgumentException("Unsupported pblite field ${info.adapter}")
      }
    }
  }

  private fun number(value: Any): Long = when (value) {
    is Number -> value.toLong()
    is String -> value.toBigInteger().toLong()
    is Boolean -> if (value) 1 else 0
    else -> throw IllegalArgumentException("Expected a number, got ${value.javaClass}")
  }

  private fun writeKey(out: Buffer, tag: Int, wireType: Int) = writeRawVarint(out, (tag.toLong() shl 3) or wireType.toLong())

  private fun writeVarint(out: Buffer, tag: Int, value: Long) {
    writeKey(out, tag, 0)
    writeRawVarint(out, value)
  }

  private fun writeBytes(out: Buffer, tag: Int, bytes: ByteArray) {
    writeKey(out, tag, 2)
    writeRawVarint(out, bytes.size.toLong())
    out.write(bytes)
  }

  private fun writeRawVarint(out: Buffer, input: Long) {
    var value = input
    while (value and 0x7FL.inv() != 0L) {
      out.writeByte(((value and 0x7F) or 0x80).toInt())
      value = value ushr 7
    }
    out.writeByte(value.toInt())
  }

  private fun base64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

  private fun unbase64(text: String) = Base64.decode(text, Base64.DEFAULT)
}

private const val PROTO = "org.thoughtcrime.securesms.googlemessages.proto"
