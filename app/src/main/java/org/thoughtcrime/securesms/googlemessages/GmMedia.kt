package org.thoughtcrime.securesms.googlemessages

import org.thoughtcrime.securesms.googlemessages.proto.MediaFormats

/** AllIn: Google Messages' media type codes. */
internal object GmMedia {
  fun formatFor(mime: String): MediaFormats = when (mime.lowercase()) {
    "image/jpeg" -> MediaFormats.IMAGE_JPEG
    "image/jpg" -> MediaFormats.IMAGE_JPG
    "image/png" -> MediaFormats.IMAGE_PNG
    "image/gif" -> MediaFormats.IMAGE_GIF
    "video/mp4" -> MediaFormats.VIDEO_MP4
    "video/3gpp" -> MediaFormats.VIDEO_3GPP
    "audio/mp4" -> MediaFormats.AUDIO_MP4
    "audio/aac" -> MediaFormats.AUDIO_AAC
    "text/vcard" -> MediaFormats.TEXT_VCARD
    "application/pdf" -> MediaFormats.APP_PDF
    else -> when (mime.substringBefore('/')) {
      "image" -> MediaFormats.IMAGE_UNSPECIFIED
      "video" -> MediaFormats.VIDEO_UNSPECIFIED
      "audio" -> MediaFormats.AUDIO_UNSPECIFIED
      else -> MediaFormats.APP_UNSPECIFIED
    }
  }

  fun isImage(format: MediaFormats, mime: String): Boolean = mime.startsWith("image/") || format.name.startsWith("IMAGE_")
}
