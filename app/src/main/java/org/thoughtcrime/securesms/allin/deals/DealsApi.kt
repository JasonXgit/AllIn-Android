package org.thoughtcrime.securesms.allin.deals

import androidx.annotation.WorkerThread
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.websession.WebProfiles
import org.thoughtcrime.securesms.websession.WebSignInActivity
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * AllIn: talks to Jason's Deal Reader (C:\Projects\RSSFeed, https://rss.jason.ky) with the session cookie from
 * [DealsSignInActivity]. The site's JSON API wants the `X-Requested-With: deal-reader` header on changes.
 *
 * Sessions are rolling (30 days from the last request), so the refreshed cookie the server sends back is copied into
 * the WebView cookie store, which keeps AllIn signed in as long as it syncs at least once a month.
 */
object DealsApi {

  private val TAG = Log.tag(DealsApi::class.java)

  const val SESSION_COOKIE = "dr.sid"

  private val JSON = "application/json".toMediaType()

  private val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .followRedirects(false)
      .build()
  }

  /** Plain client for pictures (no cookies: they're on other sites). */
  val imageClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(20, TimeUnit.SECONDS)
      .build()
  }

  val baseUrl: String get() = SignalStore.deals.serverUrl.trimEnd('/')

  fun hasSession(): Boolean = WebSignInActivity.cookie(baseUrl, SESSION_COOKIE) != null

  /** One page of cards, newest first. [view] is "unread", "starred" or "all". */
  @WorkerThread
  @Throws(DealsException::class)
  fun items(view: String, before: String?, limit: Int = 100): ItemsPage {
    val url = "$baseUrl/api/items".toHttpUrl().newBuilder()
      .addQueryParameter("view", view)
      .addQueryParameter("limit", limit.toString())
      .apply { if (before != null) addQueryParameter("before", before) }
      .build()
    val json = call(Request.Builder().url(url).get())
    val array = json.optJSONArray("items") ?: JSONArray()
    val items = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::parseDeal) }
    return ItemsPage(items, json.optBoolean("more"))
  }

  /** How many deals (duplicates counted once) are unread on the site. */
  @WorkerThread
  @Throws(DealsException::class)
  fun unreadTotal(): Int? {
    val totals = call(Request.Builder().url("$baseUrl/api/feeds").get()).optJSONObject("totals") ?: return null
    return if (totals.has("unread")) totals.optInt("unread") else null
  }

  @WorkerThread
  @Throws(DealsException::class)
  fun setStarred(id: Long, starred: Boolean) {
    post("/api/items/$id/star", JSONObject().put("starred", starred))
  }

  @WorkerThread
  @Throws(DealsException::class)
  fun markRead(ids: Collection<Long>) {
    // The server takes up to 500 per call.
    ids.chunked(400).forEach { chunk ->
      post("/api/items/read-batch", JSONObject().put("ids", JSONArray(chunk)))
    }
  }

  private fun post(path: String, body: JSONObject) {
    call(
      Request.Builder()
        .url("$baseUrl$path")
        .header("X-Requested-With", "deal-reader")
        .post(body.toString().toRequestBody(JSON))
    )
  }

  private fun call(builder: Request.Builder): JSONObject {
    val cookie = WebSignInActivity.cookie(baseUrl, SESSION_COOKIE) ?: throw DealsException.NotSignedIn()
    val request = builder
      .header("Cookie", "$SESSION_COOKIE=$cookie")
      .header("Accept", "application/json")
      .build()

    try {
      client.newCall(request).execute().use { response ->
        keepSession(response)
        // Not signed in: the API answers 401; anything else that redirects is the login page.
        if (response.code == 401 || response.isRedirect) {
          throw DealsException.NotSignedIn()
        }
        if (!response.isSuccessful) {
          Log.w(TAG, "${request.url.encodedPath} answered ${response.code}")
          throw DealsException.Failed("HTTP ${response.code}")
        }
        val text = response.body.string()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
      }
    } catch (e: IOException) {
      throw DealsException.Offline(e)
    } catch (e: JSONException) {
      throw DealsException.Failed("Bad answer", e)
    }
  }

  private fun keepSession(response: Response) {
    val setCookies = response.headers("Set-Cookie").filter { it.startsWith("$SESSION_COOKIE=") }
    if (setCookies.isEmpty()) return
    val cookies = WebProfiles.cookieManager(null)
    setCookies.forEach { cookies.setCookie(baseUrl, it) }
    cookies.flush()
  }

  private fun parseDeal(o: JSONObject): Deal? {
    val id = o.optLong("id", -1)
    if (id < 0) return null
    val links = o.optJSONArray("links") ?: JSONArray()
    val stores = (0 until links.length()).mapNotNull { i ->
      val link = links.optJSONObject(i) ?: return@mapNotNull null
      val url = link.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
      StoreLink(url = url, host = link.optString("host").ifBlank { null })
    }.distinctBy { it.host ?: it.url }.take(4)

    return Deal(
      id = id,
      title = o.optString("title").ifBlank { "(untitled)" },
      link = o.optString("link").takeIf { it.startsWith("http") },
      price = when (val p = o.opt("price")) {
        is Number -> String.format(Locale.US, "$%.2f", p.toDouble()).removeSuffix(".00")
        is String -> p.ifBlank { null }
        else -> null
      },
      feedTitle = o.optString("feed_title").ifBlank { null },
      summary = o.optString("summary").ifBlank { null }?.take(400),
      imageUrl = o.optString("image").takeIf { it.startsWith("http") },
      publishedAt = o.optLong("published_at"),
      fetchedAt = o.optLong("fetched_at"),
      read = o.optInt("is_read") == 1,
      starred = o.optInt("is_starred") == 1,
      expired = o.optInt("sd_expired") == 1,
      score = if (o.isNull("sd_score")) null else o.optInt("sd_score"),
      comments = if (o.isNull("sd_comments")) null else o.optInt("sd_comments"),
      stores = stores
    )
  }

  data class ItemsPage(val items: List<Deal>, val more: Boolean)
}

sealed class DealsException(message: String, cause: Throwable? = null) : Exception(message, cause) {
  class NotSignedIn : DealsException("Not signed in")
  class Offline(cause: Throwable) : DealsException("Can't reach the Deal Reader", cause)
  class Failed(message: String, cause: Throwable? = null) : DealsException(message, cause)
}
