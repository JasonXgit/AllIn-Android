package org.thoughtcrime.securesms.googlevoice

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.dependencies.AppDependencies
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * AllIn: names for Google Voice phone numbers, taken from the phone's own contacts (which Signal already has
 * permission to read), then from the user's Google Contacts as Google Voice reports them, then the number itself.
 */
object GoogleVoiceContacts {

  private val TAG = Log.tag(GoogleVoiceContacts::class.java)

  /** Phone contact names by number; an empty string means "not in contacts". In memory only. */
  private val cache = ConcurrentHashMap<String, String>()

  @WorkerThread
  fun displayName(phoneNumber: String, googleName: String?): String {
    return phoneContactName(phoneNumber)
      ?: googleName?.takeIf { it.isNotBlank() }
      ?: format(phoneNumber)
  }

  fun format(phoneNumber: String): String {
    return PhoneNumberUtils.formatNumber(phoneNumber, Locale.getDefault().country) ?: phoneNumber
  }

  /** Forgets cached names, so contacts added or renamed since show up. */
  fun clearCache() {
    cache.clear()
  }

  @WorkerThread
  private fun phoneContactName(phoneNumber: String): String? {
    if (phoneNumber.isBlank()) return null

    val cached = cache[phoneNumber]
    if (cached != null) return cached.ifEmpty { null }

    val context = AppDependencies.application
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
      return null
    }

    val name = try {
      val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
      context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
      }
    } catch (e: Exception) {
      Log.w(TAG, "Contact lookup failed", e)
      null
    }

    cache[phoneNumber] = name.orEmpty()
    return name?.takeIf { it.isNotBlank() }
  }
}
