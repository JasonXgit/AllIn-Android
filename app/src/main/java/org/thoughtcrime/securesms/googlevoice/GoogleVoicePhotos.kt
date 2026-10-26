package org.thoughtcrime.securesms.googlevoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.annotation.WorkerThread
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * AllIn: pictures in Google Voice conversations. Outgoing photos are shrunk to MMS size; incoming ones are kept in
 * memory only, like the rest of Google Voice data.
 */
object GoogleVoicePhotos {

  private const val MAX_SEND_DIMENSION = 1600
  private const val MAX_SEND_BYTES = 900 * 1024
  private const val MAX_SHOW_DIMENSION = 1280

  private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
  }

  /** Decodes, shrinks and re-encodes the picked image as a JPEG small enough for MMS. */
  @WorkerThread
  fun prepareForSending(context: Context, uri: Uri): Pair<ByteArray, Bitmap> {
    val bitmap = if (Build.VERSION.SDK_INT >= 28) {
      // ImageDecoder applies the photo's rotation, which BitmapFactory ignores.
      ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val scale = max(info.size.width, info.size.height).toFloat() / MAX_SEND_DIMENSION
        if (scale > 1) {
          decoder.setTargetSize((info.size.width / scale).toInt(), (info.size.height / scale).toInt())
        }
      }
    } else {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
      val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SEND_DIMENSION) }
      context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw IllegalArgumentException("Couldn't read image")
    }

    var quality = 85
    var jpeg: ByteArray
    do {
      jpeg = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
      }
      quality -= 15
    } while (jpeg.size > MAX_SEND_BYTES && quality >= 40)

    return jpeg to bitmap
  }

  /** Returns a picture from a message, downloading it the first time. */
  @WorkerThread
  @Throws(GoogleVoiceException::class)
  fun load(attachmentId: String): Bitmap? {
    cache.get(attachmentId)?.let { return it }

    val bytes = GoogleVoiceApi.downloadAttachment(attachmentId)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SHOW_DIMENSION) }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

    cache.put(attachmentId, bitmap)
    return bitmap
  }

  private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sampleSize = 1
    while (max(width, height) / (sampleSize * 2) >= maxDimension) {
      sampleSize *= 2
    }
    return sampleSize
  }
}
