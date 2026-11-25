package app.voyahradio.playback

import android.app.Notification
import android.app.Notification.MediaStyle
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioManager.OnAudioFocusChangeListener
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.voyahradio.R
import app.voyahradio.RadioApp
import app.voyahradio.data.NowPlayingFeed
import app.voyahradio.data.RadioCatalog
import app.voyahradio.data.RadioPreferences
import app.voyahradio.data.StreamProbe
import app.voyahradio.domain.Availability
import app.voyahradio.domain.Playback
import app.voyahradio.domain.RadioTab
import app.voyahradio.domain.Station
import app.voyahradio.domain.Track
import app.voyahradio.ui.RadioActivity
import app.voyahradio.ui.RadioLocale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@OptIn(markerClass = [UnstableApi::class])
class RadioService : Service() {
    inner class LocalBinder : Binder() {
        fun service(): RadioService {
            return this@RadioService
        }
    }

    private val binder: LocalBinder = LocalBinder()
    private val meter = AudioMeter()
    private val trackFeed = NowPlayingFeed()
    private val trackExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val mutablePlayback = MutableStateFlow(Playback("", Playback.Status.READY))
    val playback = mutablePlayback.asStateFlow()
    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val noisyReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.getAction() && foreground) pause()
        }
    }

    private lateinit var catalog: RadioCatalog
    private lateinit var preferences: RadioPreferences
    private lateinit var streamProbe: StreamProbe
    private var audio: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var session: MediaSession? = null
    private var openApp: PendingIntent? = null
    private var station: Station? = null
    private var currentTab = RadioTab.BELARUS
    private var track: Track? = null
    private var hasStreamTrack = false
    private var trackLookup: Future<*>? = null
    private var scheduledTrackLookup: Runnable? = null
    private var trackGeneration = 0
    private var mediaArt: Bitmap? = null
    private var notificationStrings: Context? = null
    private var player: ExoPlayer? = null
    private var transferBytes: AtomicLong? = null
    private var bufferStartBytes: Long = 0
    private var status = Playback.Status.READY
    private var foreground = false
    private var windowFocused = false
    private var focused = false
    private var waitingForFocus = false
    private var retryUsed = false
    private var allUrlsRejected = false
    private var startingNetwork: Network? = null
    private var streamIndex = 0
    private var deadline: Runnable? = null
    private var retry: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        val app = getApplication() as RadioApp
        catalog = app.catalog()
        preferences = app.preferences()
        streamProbe = app.streamProbe()
        station = catalog.find(preferences.lastStation())
        station?.let { currentTab = it.group }
        audio = getSystemService<AudioManager?>(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(OnAudioFocusChangeListener { change: Int ->
                this.focusChanged(
                    change
                )
            }, handler)
            .build()

        updateNotificationLanguage()
        val icon = getDrawable(R.drawable.radio_icon)
        if (icon != null) {
            mediaArt = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
            icon.setBounds(0, 0, mediaArt!!.getWidth(), mediaArt!!.getHeight())
            icon.draw(Canvas(mediaArt!!))
        }
        openApp = PendingIntent.getActivity(
            this, 0, Intent(this, RadioActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession(this, RadioService.TAG)
        session!!.setSessionActivity(openApp)
        session!!.setPlaybackToLocal(audioAttributes)
        session!!.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                resumePlayback()
            }

            override fun onPause() {
                pause()
            }

            override fun onStop() {
                if (foreground) finishPlayback(Playback.Status.STOPPED)
                else if (windowFocused) publish(Playback.Status.STOPPED)
            }

            override fun onSkipToNext() {
                step(1)
            }

            override fun onSkipToPrevious() {
                step(-1)
            }

            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val key = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra<KeyEvent?>(
                        Intent.EXTRA_KEY_EVENT,
                        KeyEvent::class.java
                    )
                else
                    intent.getParcelableExtra<KeyEvent?>(Intent.EXTRA_KEY_EVENT)
                if (key == null) return false
                val command: Runnable? = when (key.getKeyCode()) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> Runnable {
                        this.onPlay()
                    }

                    KeyEvent.KEYCODE_MEDIA_PAUSE -> Runnable {
                        this.onPause()
                    }

                    KeyEvent.KEYCODE_MEDIA_STOP -> Runnable {
                        this.onStop()
                    }

                    KeyEvent.KEYCODE_MEDIA_NEXT -> Runnable {
                        this.onSkipToNext()
                    }

                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Runnable {
                        this.onSkipToPrevious()
                    }

                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> Runnable {
                        if (foreground) onPause() else onPlay()
                    }

                    else -> null
                }
                if (command == null) return super.onMediaButtonEvent(intent)
                if (key.getAction() == KeyEvent.ACTION_DOWN && key.getRepeatCount() == 0) command.run()
                return true
            }
        }, handler)
        updateMetadata()
        updateSession()
        notifyObservers()

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(
            noisyReceiver,
            filter,
            RECEIVER_NOT_EXPORTED
        )
        else registerReceiver(noisyReceiver, filter)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.getAction() == null) {
            if (!foreground) stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent.getAction()) {
            PLAY -> {
                var requested = catalog.find(intent.getStringExtra(STATION_ID))
                if (requested == null) requested = catalog.all().get(0)
                currentTab = RadioTab.parse(intent.getStringExtra(TAB), requested.group) ?: requested.group
                play(requested)
            }

            RadioService.PAUSE -> pause()
            RadioService.PREVIOUS -> {
                if (foreground) step(-1)
            }

            RadioService.NEXT -> {
                if (foreground) step(1)
            }

            else -> {
                if (!foreground) stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    fun pause() {
        if (foreground) finishPlayback(Playback.Status.PAUSED)
    }

    fun setWindowFocused(hasFocus: Boolean) {
        windowFocused = hasFocus
        updateSessionActive()
    }

    private fun updateSessionActive() {
        session!!.setActive(foreground || windowFocused)
    }

    fun meter(): AudioMeter {
        return meter
    }

    fun bufferedAudioMs(): Int {
        if (player == null) return 0
        return player?.totalBufferedDuration?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
    }

    fun receivedDuringBuffering(): Long {
        return if (transferBytes == null) 0 else (transferBytes!!.get() - bufferStartBytes).coerceAtLeast(0L)
    }

    fun refreshLanguage() {
        if (notificationStrings!!.getResources().getConfiguration().getLocales().get(0)
                .getLanguage() == preferences.language()
        ) return
        updateNotificationLanguage()
        updateMetadata()
        if (foreground) startForeground(RadioService.NOTIFICATION_ID, notification())
    }

    private fun updateNotificationLanguage() {
        notificationStrings = RadioLocale.apply(this, preferences)
        getSystemService<NotificationManager?>(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                RadioService.CHANNEL,
                notificationStrings!!.getString(R.string.on_air_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun step(direction: Int) {
        if (!foreground && !windowFocused) return
        val options = preferences.catalogOptions()
        val tab = options.visibleTab(currentTab)
        val playlist = options.visibleStations(catalog.all(), tab, preferences.favorites())
        if (playlist.isEmpty()) return
        val index = if (station == null) 0 else playlist.indexOf(station)
        val next = if (index < 0)
            (if (direction > 0) 0 else playlist.size - 1)
        else
            Math.floorMod(index + direction, playlist.size)
        requestPlay(playlist.get(next)!!, tab)
    }

    private fun resumePlayback() {
        if ((!foreground && !windowFocused) || snapshot().active() || waitingForFocus) return
        val options = preferences.catalogOptions()
        val tab = options.visibleTab(currentTab)
        val saved = station
        if (saved != null && options.shows(saved) && tab.contains(saved, preferences.favorites())) {
            requestPlay(saved, tab)
            return
        }
        val candidates: MutableSet<RadioTab> = LinkedHashSet<RadioTab>()
        candidates.add(tab)
        candidates.addAll(RadioTab.entries)
        for (group in candidates) {
            if (options.hiddenTabs.contains(group)) continue
            val playlist =
                options.visibleStations(catalog.all(), group, preferences.favorites())
            if (!playlist.isEmpty()) {
                requestPlay(playlist.get(0)!!, group)
                return
            }
        }
    }

    private fun requestPlay(requested: Station, tab: RadioTab) {
        if (foreground) {
            currentTab = tab
            play(requested)
            return
        }
        try {
            startForegroundService(
                Intent(this, RadioService::class.java).setAction(PLAY)
                    .putExtra(STATION_ID, requested.id).putExtra(TAB, tab.name)
            )
        } catch (error: IllegalStateException) {
            Log.w(
                RadioService.TAG,
                "Unable to start playback from a media command",
                error
            )
        } catch (error: SecurityException) {
            Log.w(
                RadioService.TAG,
                "Unable to start playback from a media command",
                error
            )
        }
    }

    private fun play(requested: Station?) {
        if (requested == null) return
        if (foreground && requested == station && snapshot().active()) return

        cancelRetry()
        releasePlayer()
        station = requested
        preferences.rememberStation(station!!.id)
        streamIndex = 0
        retryUsed = false
        allUrlsRejected = true
        startingNetwork = streamProbe.validatedNetwork()
        updateMetadata()
        foreground = true
        status = if (waitingForFocus) Playback.Status.PAUSED else Playback.Status.CONNECTING
        updateSessionActive()
        updateSession()
        startForeground(RadioService.NOTIFICATION_ID, notification())
        notifyObservers()

        if (waitingForFocus) return
        if (!focused) {
            focused =
                audio!!.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        if (focused) connect(Playback.Status.CONNECTING)
        else finishPlayback(Playback.Status.FOCUS_ERROR)
    }

    private fun connect(connectingStatus: Playback.Status) {
        retry = null
        if (!foreground || waitingForFocus) return
        publish(connectingStatus)

        val received = AtomicLong()
        transferBytes = received
        bufferStartBytes = 0
        val network = DefaultHttpDataSource.Factory()
            .setUserAgent(StreamProbe.USER_AGENT)
            .setConnectTimeoutMs(7000)
            .setReadTimeoutMs(9000)
            .setAllowCrossProtocolRedirects(true)
            .setTransferListener(object : TransferListener {
                override fun onTransferInitializing(
                    source: DataSource,
                    spec: DataSpec,
                    network: Boolean
                ) {
                }

                override fun onTransferStart(
                    source: DataSource,
                    spec: DataSpec,
                    network: Boolean
                ) {
                }

                override fun onTransferEnd(
                    source: DataSource,
                    spec: DataSpec,
                    network: Boolean
                ) {
                }

                override fun onBytesTransferred(
                    source: DataSource, spec: DataSpec,
                    network: Boolean, count: Int
                ) {
                    if (network) received.addAndGet(count.toLong())
                }
            })
        val candidate = ExoPlayer.Builder(this, MeteringRenderersFactory(this, meter))
            .setMediaSourceFactory(DefaultMediaSourceFactory(network))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(4000, 12000, INITIAL_BUFFER_MS, REBUFFER_MS)
                    .build()
            )
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), false
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = candidate
        candidate.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (candidate !== player) return
                if (state == Player.STATE_BUFFERING && status == Playback.Status.LIVE) {
                    cancelTrackLookup()
                    bufferStartBytes = received.get()
                    publish(Playback.Status.BUFFERING)
                    armDeadline(candidate)
                } else if (state == Player.STATE_ENDED) {
                    streamFailed(candidate, "stream ended", false)
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (candidate === player && playing) {
                    cancelDeadline()
                    preferences.recordAvailability(
                        station!!.id,
                        Availability.AVAILABLE,
                        System.currentTimeMillis()
                    )
                    publish(Playback.Status.LIVE)
                    scheduleTrackLookup(0)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (candidate === player) {
                    streamFailed(candidate, error!!.getErrorCodeName(), endpointRejected(error))
                }
            }

            override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                if (candidate !== player) return
                val updated: Track? =
                    Track.from(metadata!!.title, metadata.artist, station!!.name)
                if (updated != null) {
                    hasStreamTrack = true
                    cancelTrackLookup()
                    setTrack(updated)
                } else if (hasStreamTrack) {
                    hasStreamTrack = false
                    setTrack(null)
                    scheduleTrackLookup(0)
                }
            }
        })
        candidate.setMediaItem(MediaItem.fromUri(station!!.streams.get(streamIndex)))
        candidate.setPlayWhenReady(true)
        armDeadline(candidate)
        candidate.prepare()
    }

    private fun endpointRejected(error: PlaybackException?): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                return cause.dataSpec.uri.toString() == station!!.streams.get(streamIndex)
                        && Availability.isDefinitiveHttpFailure(cause.responseCode)
            }
            cause = cause.cause
        }
        return false
    }

    private fun setTrack(updated: Track?) {
        if (updated == track) return
        track = updated
        updateMetadata()
        if (foreground) startForeground(RadioService.NOTIFICATION_ID, notification())
        notifyObservers()
    }

    private fun scheduleTrackLookup(delay: Long) {
        if (!foreground || status != Playback.Status.LIVE || hasStreamTrack || station!!.trackSourceId <= 0 || scheduledTrackLookup != null || trackLookup != null) return
        val requested = station
        val generation = trackGeneration
        scheduledTrackLookup = Runnable {
            scheduledTrackLookup = null
            trackLookup = trackExecutor.submit(Runnable {
                val result = trackFeed.read(requested!!.trackSourceId, requested.name)
                handler.post {
                    if (generation != trackGeneration) return@post
                    trackLookup = null
                    setTrack(result)
                    scheduleTrackLookup(RadioService.TRACK_REFRESH_MS)
                }
            })
        }
        handler.postDelayed(scheduledTrackLookup!!, delay)
    }

    private fun cancelTrackLookup() {
        trackGeneration++
        if (scheduledTrackLookup != null) handler.removeCallbacks(scheduledTrackLookup!!)
        scheduledTrackLookup = null
        if (trackLookup != null) trackLookup!!.cancel(true)
        trackLookup = null
    }

    private fun streamFailed(source: ExoPlayer?, reason: String?, rejected: Boolean) {
        if (source !== player) return
        allUrlsRejected = allUrlsRejected and (rejected && streamProbe.isCurrent(startingNetwork))
        Log.w(
            RadioService.TAG,
            "Stream failed for " + station!!.id + " (source " + (streamIndex + 1) + "): " + reason
        )
        releasePlayer()
        if (streamIndex + 1 < station!!.streams.size) {
            streamIndex++
        } else if ((status == Playback.Status.LIVE || status == Playback.Status.BUFFERING) && !retryUsed) {
            retryUsed = true
            streamIndex = 0
        } else {
            if (allUrlsRejected) {
                preferences.recordAvailability(
                    station!!.id,
                    Availability.UNAVAILABLE,
                    System.currentTimeMillis()
                )
            }
            finishPlayback(if (streamProbe.validatedNetwork() == null) Playback.Status.NETWORK_ERROR else Playback.Status.STREAM_ERROR)
            return
        }

        publish(Playback.Status.RECONNECTING)
        retry = Runnable { connect(Playback.Status.RECONNECTING) }
        handler.postDelayed(retry!!, 800)
    }

    private fun focusChanged(change: Int) {
        if (!foreground) return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> finishPlayback(Playback.Status.PAUSED)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                waitingForFocus = true
                cancelRetry()
                releasePlayer()
                publish(Playback.Status.PAUSED)
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (player != null) player!!.setVolume(0.2f)
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                if (waitingForFocus) {
                    waitingForFocus = false
                    connect(Playback.Status.CONNECTING)
                } else if (player != null) {
                    player!!.setVolume(1f)
                }
            }
        }
    }

    private fun finishPlayback(result: Playback.Status) {
        cancelRetry()
        releasePlayer()
        if (focused) audio!!.abandonAudioFocusRequest(focusRequest!!)
        focused = false
        waitingForFocus = false
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        publish(result)
        updateSessionActive()
        stopSelf()
    }

    private fun snapshot(): Playback {
        return Playback(if (station == null) "" else station!!.id, status, track)
    }

    private fun publish(updated: Playback.Status) {
        if (status == updated) return
        status = updated
        updateSession()
        if (foreground) startForeground(RadioService.NOTIFICATION_ID, notification())
        notifyObservers()
    }

    private fun notifyObservers() {
        mutablePlayback.value = snapshot()
    }

    private fun updateMetadata() {
        if (station == null) return
        val title = if (track == null) station!!.name else track!!.title
        val artist =
            if (track == null || track!!.artist.isEmpty()) station!!.name else track!!.artist
        val metadata = android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID, station!!.id)
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, station!!.name)
            .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(
                android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, if (track == null)
                    station!!.displayLabel(
                        notificationStrings!!.getResources().getConfiguration().getLocales().get(0)
                    )
                else
                    artist
            )
            .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, station!!.name)
            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, -1)
        if (mediaArt != null) metadata.putBitmap(
            android.media.MediaMetadata.METADATA_KEY_ART,
            mediaArt
        )
        session!!.setMetadata(metadata.build())
    }

    private fun updateSession() {
        val platformState = when (status) {
            Playback.Status.CONNECTING, Playback.Status.BUFFERING, Playback.Status.RECONNECTING -> PlaybackState.STATE_BUFFERING
            Playback.Status.LIVE -> PlaybackState.STATE_PLAYING
            Playback.Status.PAUSED -> PlaybackState.STATE_PAUSED
            Playback.Status.STOPPED -> PlaybackState.STATE_STOPPED
            Playback.Status.STREAM_ERROR, Playback.Status.NETWORK_ERROR, Playback.Status.FOCUS_ERROR -> PlaybackState.STATE_ERROR
            Playback.Status.READY -> PlaybackState.STATE_NONE
        }
        val actions = (PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY
                or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP
                or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        session!!.setPlaybackState(
            PlaybackState.Builder().setActions(actions)
                .setState(
                    platformState, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (status == Playback.Status.LIVE) 1f else 0f
                )
                .build()
        )
    }

    private fun notification(): Notification {
        val active = snapshot().active()
        val subtitle = if (track == null)
            notificationStrings!!.getString(PlaybackText.label(status))
        else
            if (track!!.artist.isEmpty())
                station!!.name
            else
                notificationStrings!!.getString(
                    R.string.track_notification,
                    track!!.artist,
                    station!!.name
                )
        return Notification.Builder(this, RadioService.CHANNEL)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(if (track == null) station!!.name else track!!.title)
            .setContentText(subtitle)
            .setContentIntent(openApp)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(
                action(
                    android.R.drawable.ic_media_previous,
                    R.string.previous,
                    RadioService.PREVIOUS
                )
            )
            .addAction(
                action(
                    if (active) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (active) R.string.pause else R.string.play,
                    if (active) RadioService.PAUSE else PLAY
                )
            )
            .addAction(
                action(
                    android.R.drawable.ic_media_next,
                    R.string.next,
                    RadioService.NEXT
                )
            )
            .setStyle(
                MediaStyle().setMediaSession(session!!.getSessionToken())
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun action(icon: Int, title: Int, command: String?): Notification.Action {
        val intent = Intent(this, RadioService::class.java).setAction(command)
        if (PLAY == command) intent.putExtra(STATION_ID, station!!.id)
            .putExtra(TAB, currentTab.name)
        val pending = PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(icon, notificationStrings!!.getString(title), pending)
            .build()
    }

    private fun armDeadline(source: ExoPlayer?) {
        cancelDeadline()
        deadline = Runnable { streamFailed(source, "timeout", false) }
        handler.postDelayed(deadline!!, RadioService.STREAM_TIMEOUT_MS)
    }

    private fun cancelDeadline() {
        if (deadline != null) handler.removeCallbacks(deadline!!)
        deadline = null
    }

    private fun cancelRetry() {
        if (retry != null) handler.removeCallbacks(retry!!)
        retry = null
    }

    private fun releasePlayer() {
        cancelDeadline()
        cancelTrackLookup()
        hasStreamTrack = false
        val released = player
        player = null
        transferBytes = null
        if (released != null) released.release()
        if (track != null) {
            track = null
            updateMetadata()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(noisyReceiver)
        cancelRetry()
        releasePlayer()
        trackExecutor.shutdownNow()
        if (focused) audio!!.abandonAudioFocusRequest(focusRequest!!)
        session!!.release()
        super.onDestroy()
    }

    companion object {
        const val PLAY: String = "app.voyahradio.PLAY"
        const val STATION_ID: String = "station_id"
        const val INITIAL_BUFFER_MS: Int = 1000
        const val REBUFFER_MS: Int = 2000
        const val TAB: String = "tab"
        private const val PAUSE = "app.voyahradio.PAUSE"
        private const val PREVIOUS = "app.voyahradio.PREVIOUS"
        private const val NEXT = "app.voyahradio.NEXT"
        private const val CHANNEL = "radio_playback"
        private const val NOTIFICATION_ID = 10
        private const val STREAM_TIMEOUT_MS: Long = 12000
        private const val TRACK_REFRESH_MS: Long = 25000
        private const val TAG = "VoyahRadio"
    }
}
