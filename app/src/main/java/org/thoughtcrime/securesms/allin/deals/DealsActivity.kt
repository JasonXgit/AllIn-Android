package org.thoughtcrime.securesms.allin.deals

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.core.ui.compose.Dividers
import org.signal.core.ui.compose.Scaffolds
import org.signal.core.ui.compose.SignalIcons
import org.signal.core.ui.compose.theme.SignalTheme
import org.thoughtcrime.securesms.BaseActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme
import java.text.NumberFormat

/**
 * AllIn: the Deal Reader's cards, read from the copy saved on the phone ([DealsStore]), so it works offline.
 *
 * Stars and reads apply at once and are sent on the next sync. Like the website, a card counts as read once it has
 * scrolled off the top. Tapping a card shows its summary and store links.
 */
class DealsActivity : BaseActivity() {

  companion object {
    @JvmStatic
    fun createIntent(context: Context): Intent {
      return Intent(context, DealsActivity::class.java)
    }
  }

  private val theme = DynamicNoActionBarTheme()

  override fun onCreate(savedInstanceState: Bundle?) {
    theme.onCreate(this)
    super.onCreate(savedInstanceState)

    setContent {
      SignalTheme {
        val data by DealsStore.data.collectAsState()
        val status by DealsSync.status.collectAsState()
        val imagesVersion by DealsSync.imagesVersion.collectAsState()

        DealsScreen(
          data = data,
          status = status,
          imagesVersion = imagesVersion,
          onSyncClick = {
            if (!DealsSync.isOnline()) toast("You're offline. Your changes are saved and will be sent when you're back online.")
            DealsSync.requestSync(force = true)
          },
          onSignInClick = { startActivity(DealsSignInActivity.createIntent(this)) },
          onStarClick = { deal -> DealsStore.setStarred(deal.id, !deal.starred) },
          onScrolledPast = { ids -> DealsStore.markRead(ids) },
          onOpen = { deal, url -> open(deal, url) },
          onNavigationClick = { finish() }
        )
      }
    }
  }

  override fun onResume() {
    super.onResume()
    theme.onResume(this)
    DealsSync.requestSync()
  }

  private fun open(deal: Deal, url: String) {
    DealsStore.markRead(listOf(deal.id))
    if (!DealsSync.isOnline()) {
      toast("You're offline. Star it to find it later.")
      return
    }
    try {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
      toast("No app can open that link.")
    }
  }

  private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

private enum class DealsFilter(val label: String) { UNREAD("Unread"), STARRED("Starred"), ALL("All saved") }

@Composable
private fun DealsScreen(
  data: DealsData,
  status: DealsSync.Status,
  imagesVersion: Int,
  onSyncClick: () -> Unit,
  onSignInClick: () -> Unit,
  onStarClick: (Deal) -> Unit,
  onScrolledPast: (List<Long>) -> Unit,
  onOpen: (Deal, String) -> Unit,
  onNavigationClick: () -> Unit
) {
  var filter by rememberSaveable { mutableStateOf(DealsFilter.UNREAD) }
  var expanded by rememberSaveable { mutableStateOf<Long?>(null) }
  // Cards read while this screen is open stay in the Unread list until it's reopened, so nothing jumps.
  val readHere = remember { mutableStateListOf<Long>() }

  val shown = when (filter) {
    DealsFilter.UNREAD -> data.deals.filter { !it.read || it.id in readHere }
    DealsFilter.STARRED -> data.deals.filter { it.starred }
    DealsFilter.ALL -> data.deals
  }

  Scaffolds.Settings(
    title = "Deals",
    onNavigationClick = onNavigationClick,
    navigationIcon = SignalIcons.ArrowStart.imageVector,
    actions = {
      TextButton(onClick = onSyncClick, enabled = status != DealsSync.Status.Syncing) {
        Text(if (status == DealsSync.Status.Syncing) "Syncing…" else "Sync")
      }
    }
  ) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
    ) {
      StatusLine(data, status, onSignInClick)

      Row(
        modifier = Modifier
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        val unread = data.deals.count { !it.read }
        val starred = data.deals.count { it.starred }
        DealsFilter.entries.forEach { f ->
          val count = when (f) {
            DealsFilter.UNREAD -> unread
            DealsFilter.STARRED -> starred
            DealsFilter.ALL -> data.deals.size
          }
          FilterChip(
            selected = filter == f,
            onClick = {
              filter = f
              readHere.clear()
            },
            label = { Text("${f.label} ${number(count)}") }
          )
        }
      }

      if (shown.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = when {
              data.lastSync == 0L -> "No deals saved yet. They download the first time AllIn syncs while you're online."
              filter == DealsFilter.STARRED -> "Nothing starred yet. Tap ☆ on a deal to save it for later."
              else -> "All caught up."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        return@Column
      }

      val listState = rememberLazyListState()
      val currentShown by rememberUpdatedState(shown)
      LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { first ->
          val past = currentShown.take(first).filter { !it.read }.map { it.id }
          if (past.isNotEmpty()) {
            readHere.addAll(past)
            onScrolledPast(past)
          }
        }
      }
      LaunchedEffect(filter) { listState.scrollToItem(0) }

      LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(shown, key = { it.id }) { deal ->
          DealCard(
            deal = deal,
            expanded = expanded == deal.id,
            imagesVersion = imagesVersion,
            onClick = { expanded = if (expanded == deal.id) null else deal.id },
            onStarClick = { onStarClick(deal) },
            onOpen = { url -> onOpen(deal, url) }
          )
          Dividers.Default()
        }
        // Room for the last cards to scroll off the top (and count as read).
        item { Spacer(modifier = Modifier.height(480.dp)) }
      }
    }
  }
}

@Composable
private fun StatusLine(data: DealsData, status: DealsSync.Status, onSignInClick: () -> Unit) {
  val synced = when {
    data.lastSync == 0L -> "never synced"
    age(data.lastSync) == "just now" -> "synced just now"
    else -> "synced ${age(data.lastSync)} ago"
  }
  val waiting = data.pendingCount.takeIf { it > 0 }?.let { " · $it change${if (it == 1) "" else "s"} to send" } ?: ""
  val text = when (status) {
    DealsSync.Status.Syncing -> "Syncing…$waiting"
    DealsSync.Status.Offline -> "Offline · $synced$waiting"
    DealsSync.Status.SignedOut -> "Signed out of the Deal Reader$waiting"
    is DealsSync.Status.Failed -> "Couldn't sync (${status.message}) · $synced$waiting"
    DealsSync.Status.Idle -> {
      val more = data.unreadOnServer?.let { total ->
        val local = data.deals.count { !it.read }
        if (total > local) " · ${number(total)} unread on the site, newest ${number(local)} saved" else ""
      } ?: ""
      "${synced.replaceFirstChar { it.uppercase() }}$more$waiting"
    }
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = text.replaceFirstChar { it.uppercase() },
      style = MaterialTheme.typography.bodySmall,
      color = if (status == DealsSync.Status.SignedOut) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f)
    )
    if (status == DealsSync.Status.SignedOut) {
      TextButton(onClick = onSignInClick) { Text("Sign in") }
    }
  }
}

@Composable
private fun DealCard(
  deal: Deal,
  expanded: Boolean,
  imagesVersion: Int,
  onClick: () -> Unit,
  onStarClick: () -> Unit,
  onOpen: (String) -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(end = 4.dp, top = 12.dp, bottom = 12.dp),
    verticalAlignment = Alignment.Top
  ) {
    // Unread bar, like the website.
    Box(
      modifier = Modifier
        .width(4.dp)
        .height(72.dp)
        .background(if (deal.read) Color.Transparent else MaterialTheme.colorScheme.primary)
    )
    Spacer(modifier = Modifier.width(12.dp))

    DealImage(deal, imagesVersion)

    Column(
      modifier = Modifier
        .weight(1f)
        .padding(start = 12.dp)
    ) {
      Text(
        text = deal.title,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (deal.read) FontWeight.Normal else FontWeight.SemiBold,
        color = if (deal.expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        maxLines = if (expanded) 8 else 3,
        overflow = TextOverflow.Ellipsis
      )

      val meta = buildList {
        if (deal.expired) add("EXPIRED")
        deal.feedTitle?.let { add(it) }
        if (deal.fetchedAt > 0) add(age(deal.fetchedAt))
        deal.score?.let { add("👍 $it") }
        deal.comments?.takeIf { it > 0 }?.let { add("💬 $it") }
      }.joinToString(" · ")

      Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        deal.price?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp)
          )
        }
        Text(
          text = meta,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      if (expanded) {
        deal.summary?.let {
          Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
          )
        }
        Row(
          modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(top = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          deal.link?.let { link -> AssistChip(onClick = { onOpen(link) }, label = { Text("Open deal") }) }
          deal.stores.forEach { store ->
            AssistChip(onClick = { onOpen(store.url) }, label = { Text(store.host ?: "Store") })
          }
        }
      }
    }

    IconButton(onClick = onStarClick) {
      Icon(
        painter = painterResource(if (deal.starred) R.drawable.symbol_star_24 else R.drawable.symbol_star_outline_24),
        contentDescription = if (deal.starred) "Unstar" else "Star",
        tint = if (deal.starred) Color(0xFFF5B400) else MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }
}

@Composable
private fun DealImage(deal: Deal, imagesVersion: Int) {
  val bitmap by produceState<ImageBitmap?>(initialValue = null, deal.id, imagesVersion) {
    value = withContext(Dispatchers.IO) {
      val file = DealsStore.imageFile(deal.id)
      if (file.exists()) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
    }
  }

  Box(
    modifier = Modifier
      .size(72.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center
  ) {
    val image = bitmap
    if (image != null) {
      Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
      Icon(
        painter = painterResource(R.drawable.symbol_tag_24),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(28.dp)
      )
    }
  }
}

private fun number(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/** "5m", "3h", "2d". */
internal fun age(time: Long): String {
  val minutes = ((System.currentTimeMillis() - time) / 60_000).coerceAtLeast(0)
  return when {
    minutes < 1 -> "just now"
    minutes < 60 -> "${minutes}m"
    minutes < 48 * 60 -> "${minutes / 60}h"
    else -> "${minutes / (24 * 60)}d"
  }
}
