package org.thoughtcrime.securesms.googlevoice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.Rows
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme
import java.util.Locale

/**
 * AllIn: starts a new Google Voice conversation. Recipients come from the phone's contacts or a typed number;
 * the first text creates the conversation (sent with no thread ID and the recipients' numbers, like the web app),
 * which then opens in [GoogleVoiceConversationActivity].
 */
class GoogleVoiceNewTextActivity : BaseActivity() {

  companion object {
    private val TAG = Log.tag(GoogleVoiceNewTextActivity::class.java)

    @JvmStatic
    fun createIntent(context: Context): Intent = Intent(context, GoogleVoiceNewTextActivity::class.java)
  }

  private val theme = DynamicNoActionBarTheme()

  private var query by mutableStateOf("")
  private var recipients by mutableStateOf<List<Suggestion>>(emptyList())
  private var suggestions by mutableStateOf<List<Suggestion>>(emptyList())
  private var draft by mutableStateOf("")
  private var attachment by mutableStateOf<PreparedPhoto?>(null)
  private var status by mutableStateOf<String?>(null)
  private var sending by mutableStateOf(false)

  private var searchJob: Job? = null
  private lateinit var signer: GoogleVoiceSigner

  override fun onCreate(savedInstanceState: Bundle?) {
    theme.onCreate(this)
    super.onCreate(savedInstanceState)

    signer = GoogleVoiceSigner(this)
    signer.warmUp()

    setContent {
      SignalTheme {
        Scaffolds.Settings(
          title = "New Google Voice text",
          onNavigationClick = { finish() },
          navigationIcon = SignalIcons.ArrowStart.imageVector,
          bottomBar = {
            ComposeBar(
              draft = draft,
              attachment = attachment?.preview,
              onDraftChange = { draft = it },
              onPhotoPicked = ::onPhotoPicked,
              onRemoveAttachment = { attachment = null },
              onSendClick = ::send
            )
          }
        ) { paddingValues ->
          Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            ToField()
            HorizontalDivider()
            Text(
              text = status ?: if (sending) "Sending…" else "Sent from your Google Voice number. Not end-to-end encrypted.",
              style = MaterialTheme.typography.bodySmall,
              color = if (status != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
              val typed = toE164(query)
              if (typed != null) {
                item {
                  Rows.TextRow(
                    text = "Text ${GoogleVoiceContacts.format(typed)}",
                    onClick = { add(Suggestion(GoogleVoiceContacts.format(typed), typed)) }
                  )
                }
              }
              items(suggestions, key = { it.number }) { suggestion ->
                Rows.TextRow(
                  text = suggestion.name,
                  label = GoogleVoiceContacts.format(suggestion.number),
                  onClick = { add(suggestion) }
                )
              }
            }
          }
        }
      }
    }
  }

  @Composable
  private fun ToField() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
      Text("To:", style = MaterialTheme.typography.bodyLarge)
      Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(start = 8.dp).horizontalScroll(rememberScrollState())
      ) {
        recipients.forEach { recipient ->
          Text(
            text = "${recipient.name} ×",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
              .background(GoogleVoiceAccent.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
              .clickable { recipients = recipients - recipient }
              .padding(horizontal = 10.dp, vertical = 4.dp)
          )
        }
      }
      TextField(
        value = query,
        onValueChange = ::onQueryChanged,
        placeholder = { Text("Name or phone number") },
        singleLine = true,
        colors = TextFieldDefaults.colors(
          focusedContainerColor = Color.Transparent,
          unfocusedContainerColor = Color.Transparent,
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent
        ),
        modifier = Modifier.fillMaxWidth()
      )
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
  }

  override fun onDestroy() {
    signer.destroy()
    super.onDestroy()
  }

  private fun onQueryChanged(value: String) {
    query = value
    searchJob?.cancel()
    searchJob = lifecycleScope.launch {
      delay(150)
      suggestions = withContext(Dispatchers.IO) { searchContacts(value.trim()) }
    }
  }

  private fun add(suggestion: Suggestion) {
    if (recipients.none { it.number == suggestion.number }) {
      recipients = recipients + suggestion
    }
    query = ""
    suggestions = emptyList()
  }

  private fun onPhotoPicked(uri: Uri) {
    lifecycleScope.launch {
      try {
        val (jpeg, preview) = withContext(Dispatchers.IO) { GoogleVoicePhotos.prepareForSending(this@GoogleVoiceNewTextActivity, uri) }
        attachment = PreparedPhoto(jpeg, preview)
      } catch (e: Exception) {
        Log.w(TAG, "Couldn't read photo", e)
        status = "Couldn't use that photo."
      }
    }
  }

  private fun send() {
    val numbers = (recipients.map { it.number } + listOfNotNull(toE164(query))).distinct()
    val text = draft.trim()
    val photo = attachment
    if (numbers.isEmpty()) {
      status = "Add who to text first."
      return
    }
    if ((text.isEmpty() && photo == null) || sending) return

    sending = true
    status = null
    lifecycleScope.launch {
      try {
        val transactionId = GoogleVoiceApi.newTransactionId()
        val signature = signer.sign("", numbers, transactionId) ?: "!".also { Log.w(TAG, "Sending without a signature") }
        val response = withContext(Dispatchers.IO) {
          GoogleVoiceApi.sendSms("", text, transactionId, signature, photo?.jpeg, recipients = numbers)
        }
        withContext(Dispatchers.IO) { GoogleVoiceMonitor.requestCheck() }

        val name = (recipients.map { it.name } + listOfNotNull(toE164(query)?.let { GoogleVoiceContacts.format(it) })).distinct().joinToString(", ")
        if (response.threadID.isNotBlank()) {
          startActivity(GoogleVoiceConversationActivity.createIntent(this@GoogleVoiceNewTextActivity, response.threadID, name))
        }
        finish()
      } catch (e: GoogleVoiceException) {
        Log.w(TAG, "Couldn't send", e)
        status = if (e is GoogleVoiceException.NotSignedIn) "Signed out of Google Voice. Sign in again in Settings." else "Not sent. Check the number and try again."
      } finally {
        sending = false
      }
    }
  }

  /** The phone's contacts whose name or number matches [query]. */
  private fun searchContacts(query: String): List<Suggestion> {
    if (query.isEmpty() || ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
      return emptyList()
    }
    val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
    val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
    return try {
      contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        val results = mutableListOf<Suggestion>()
        while (cursor.moveToNext() && results.size < 20) {
          val number = cursor.getString(1)?.let { toE164(it) } ?: continue
          results += Suggestion(cursor.getString(0) ?: GoogleVoiceContacts.format(number), number)
        }
        results.distinctBy { it.number }
      } ?: emptyList()
    } catch (e: Exception) {
      Log.w(TAG, "Contact search failed", e)
      emptyList()
    }
  }

  /** "+15551234567" from what the user typed or a contact's number, or null if it isn't a phone number. */
  private fun toE164(input: String): String? {
    val digits = input.filter { it.isDigit() || it == '+' }
    if (digits.count { it.isDigit() } < 7) return null
    return PhoneNumberUtils.formatNumberToE164(digits, Locale.getDefault().country.ifBlank { "US" })
  }

  data class Suggestion(val name: String, val number: String)
}

/** AllIn: the "New Google Voice text" choice at the top of Signal's new-message screen. */
@Composable
fun NewGoogleVoiceTextRow(onClick: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 24.dp, vertical = 14.dp)
  ) {
    Text(
      text = "#",
      color = GoogleVoiceAccent,
      style = MaterialTheme.typography.titleMedium,
      modifier = Modifier
        .background(GoogleVoiceAccent.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
        .padding(horizontal = 14.dp, vertical = 8.dp)
    )
    Column(modifier = Modifier.padding(start = 16.dp)) {
      Text("New Google Voice text", style = MaterialTheme.typography.bodyLarge)
      Text(
        "Send an SMS from your Google Voice number",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}
