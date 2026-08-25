package eu.kanade.tachiyomi.ui.reader.viewer.video

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import okhttp3.Credentials
import tachiyomi.source.komga.KomgaPreferences

@UnstableApi
class VideoViewer(
    private val activity: ReaderActivity,
) : Viewer {

    private val komgaPreferences: KomgaPreferences? by lazy {
        try {
            KomgaPreferences(activity)
        } catch (_: Exception) {
            null
        }
    }

    private val container: FrameLayout = FrameLayout(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(0xFF000000.toInt())
    }

    private val playerView: PlayerView = PlayerView(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        useController = true
        setShowNextButton(false)
        setShowPreviousButton(false)
        setShowFastForwardButton(true)
        setShowRewindButton(true)
    }

    private var player: ExoPlayer? = null
    private var currentChapters: ViewerChapters? = null
    private var hasReportedProgress = false

    init {
        container.addView(playerView)
    }

    private fun buildDataSourceFactory(): DataSource.Factory {
        val prefs = komgaPreferences
        val headers = mutableMapOf<String, String>()
        if (prefs != null) {
            if (prefs.apiKey.isNotBlank()) {
                headers["X-API-Key"] = prefs.apiKey
            } else if (prefs.username.isNotBlank()) {
                headers["Authorization"] = Credentials.basic(prefs.username, prefs.password)
            }
        }
        return DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
    }

    private fun createPlayer(): ExoPlayer {
        return ExoPlayer.Builder(activity)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(buildDataSourceFactory()),
            )
            .build()
            .also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            markAsRead()
                        } else if (playbackState == Player.STATE_READY && !hasReportedProgress) {
                            hasReportedProgress = true
                            reportPageSelected()
                        }
                    }
                })
            }
    }

    private fun playCurrentChapter() {
        val chapters = currentChapters ?: return
        val chapter = chapters.currChapter
        val pages = chapter.pages
        if (pages.isNullOrEmpty()) return

        val page = pages.first()
        val streamUrl = page.imageUrl ?: return

        if (player == null) {
            player = createPlayer()
            playerView.player = player
        }

        hasReportedProgress = false
        player?.apply {
            setMediaItem(MediaItem.fromUri(streamUrl))
            prepare()
            playWhenReady = true
        }
    }

    private fun markAsRead() {
        val chapters = currentChapters ?: return
        val page = chapters.currChapter.pages?.firstOrNull() ?: return
        activity.onPageSelected(page)
    }

    private fun reportPageSelected() {
        val chapters = currentChapters ?: return
        val page = chapters.currChapter.pages?.firstOrNull() ?: return
        activity.onPageSelected(page)
    }

    override fun getView(): View = container

    override fun destroy() {
        player?.release()
        player = null
        playerView.player = null
    }

    override fun setChapters(chapters: ViewerChapters) {
        currentChapters?.unref()
        chapters.ref()
        currentChapters = chapters
        playCurrentChapter()
    }

    override fun moveToPage(page: ReaderPage) {
        // Video books have a single "page" (the stream URL).
        // Reload the chapter if this is a different chapter's page.
        val chapters = currentChapters
        if (chapters != null && page.chapter !== chapters.currChapter) {
            currentChapters = ViewerChapters(
                currChapter = page.chapter,
                prevChapter = null,
                nextChapter = null,
            )
            playCurrentChapter()
        }
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_SPACE,
            -> {
                player?.let { p ->
                    p.playWhenReady = !p.playWhenReady
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            -> {
                player?.seekForward()
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_DPAD_LEFT,
            -> {
                player?.seekBack()
                true
            }
            else -> false
        }
    }

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false
}
