package app.voyahradio.ui

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.voyahradio.R
import app.voyahradio.RadioApp
import app.voyahradio.data.RadioPreferences
import app.voyahradio.domain.RadioTab
import app.voyahradio.playback.RadioService
import app.voyahradio.presentation.RadioHome
import app.voyahradio.presentation.RadioSettings
import app.voyahradio.presentation.RadioViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class RadioActivity : ComponentActivity() {
    private val model by viewModels<RadioViewModel> {
        viewModelFactory {
            initializer {
                val app = application as RadioApp
                RadioViewModel(app.catalog(), app.preferences(), app.streamProbe())
            }
        }
    }
    private val service = MutableStateFlow<RadioService?>(null)
    private var playbackUpdates: Job? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val player = (binder as RadioService.LocalBinder).service()
            service.value = player
            player.setWindowFocused(hasWindowFocus())
            player.refreshLanguage()
            playbackUpdates?.cancel()
            playbackUpdates = lifecycleScope.launch { player.playback.collect(model::playbackChanged) }
        }

        override fun onServiceDisconnected(name: ComponentName) = detach()
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(RadioLocale.apply(base, RadioPreferences(base)))
    }

    override fun onCreate(saved: Bundle?) {
        RadioTheme.apply(this, RadioPreferences(this).theme())
        super.onCreate(saved)
        volumeControlStream = AudioManager.STREAM_MUSIC
        saved?.let {
            model.restore(RadioTab.parse(it.getString("tab"), null), it.getString("query").orEmpty(),
                it.getBoolean("settings"))
            RadioTab.parse(it.getString("group"), null)?.let(model::chooseGroup)
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val player by service.collectAsStateWithLifecycle()
            val screens = rememberSaveableStateHolder()
            LaunchedEffect(state.settings, player) {
                player?.setWindowFocused(hasWindowFocus() && !state.settings)
            }
            BackHandler(state.settings) { model.openSettings(false) }
            RadioPalette(state.theme == "amber") {
                val render: @androidx.compose.runtime.Composable (Boolean) -> Unit = { settings ->
                    if (settings) RadioSettings(state, model)
                    else screens.SaveableStateProvider("catalog") { RadioHome(state, model, player, !state.settings) }
                }
                if (ValueAnimator.areAnimatorsEnabled()) {
                    AnimatedContent(state.settings, transitionSpec = {
                        (fadeIn(tween(190)) + slideInVertically(tween(200)) { if (targetState) it / 24 else -it / 24 })
                            .togetherWith(fadeOut(tween(130)))
                    }, label = "Radio destination") { render(it) }
                } else render(state.settings)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.effects.collect { action ->
                    when (action) {
                        is RadioViewModel.Action.Play -> play(action)
                        RadioViewModel.Action.Pause -> service.value?.pause()
                        RadioViewModel.Action.KeepOneTab -> Toast.makeText(
                            this@RadioActivity, R.string.keep_one_tab, Toast.LENGTH_SHORT).show()
                        RadioViewModel.Action.ChangeLanguage -> recreate()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        val state = model.state.value
        out.putString("tab", state.tab.name)
        out.putString("query", state.query)
        out.putString("group", state.group.name)
        out.putBoolean("settings", state.settings)
        super.onSaveInstanceState(out)
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, RadioService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        detach()
        if (bound) unbindService(connection)
        bound = false
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        service.value?.setWindowFocused(hasFocus && !model.state.value.settings)
    }

    private fun detach() {
        playbackUpdates?.cancel()
        playbackUpdates = null
        service.value?.setWindowFocused(false)
        service.value = null
    }

    private fun play(action: RadioViewModel.Action.Play) {
        try {
            startForegroundService(Intent(this, RadioService::class.java)
                .setAction(RadioService.PLAY)
                .putExtra(RadioService.STATION_ID, action.id)
                .putExtra(RadioService.TAB, action.tab.name))
        } catch (_: IllegalStateException) {
            Toast.makeText(this, R.string.stream_error, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.stream_error, Toast.LENGTH_SHORT).show()
        }
    }
}
