package app.voyahradio.presentation

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.voyahradio.R
import app.voyahradio.domain.Playback
import app.voyahradio.domain.RadioTab
import app.voyahradio.domain.Station
import app.voyahradio.playback.AudioMeter
import app.voyahradio.playback.PlaybackText
import app.voyahradio.playback.RadioService
import app.voyahradio.ui.*
import app.voyahradio.ui.RadioGlyph.Kind
import kotlinx.coroutines.delay
import kotlin.math.max

@Composable
internal fun RadioHome(state: RadioViewModel.State, model: RadioViewModel, service: RadioService?, visible: Boolean) {
    val focus = LocalFocusManager.current
    val config = LocalConfiguration.current
    val width = config.screenWidthDp
    val wide = width >= 720
    val inline = width >= 900
    val compact = !wide || config.screenHeightDp < 510
    val outer = if (wide) 28 else 16
    val openSettings = {
        focus.clearFocus()
        model.chooseGroup(RadioTab.BELARUS)
        model.openSettings(true)
    }
    Column(Modifier.fillMaxSize().background(Background)
        .padding(start = outer.dp, end = outer.dp, top = if (wide) 16.dp else 12.dp, bottom = if (wide) 18.dp else 12.dp)) {
        if (inline) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioText(stringResource(R.string.app_name), 24, Modifier.weight(1f), medium = true)
                HomeTabs(state, model, width)
                Spacer(Modifier.width(8.dp))
                Tool(Kind.SETTINGS, stringResource(R.string.settings), openSettings, Modifier.size(52.dp))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioText(stringResource(R.string.app_name), 24, Modifier.weight(1f), medium = true)
                Tool(Kind.SETTINGS, stringResource(R.string.settings), openSettings, Modifier.size(52.dp))
            }
            Spacer(Modifier.height(12.dp))
            HomeTabs(state, model, width, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(if (wide) 18.dp else 12.dp))
        if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                StationPlayer(state, model, service, compact, visible, Modifier.width((width * 34 / 100).coerceIn(292, 440).dp).fillMaxHeight())
                Catalog(state, model, Modifier.weight(1f).fillMaxHeight(), if (width >= 1000) 2 else 1)
            }
        } else {
            StationPlayer(state, model, service, true, visible, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Catalog(state, model, Modifier.weight(1f), 1)
        }
    }
}

@Composable
private fun HomeTabs(state: RadioViewModel.State, model: RadioViewModel, width: Int, modifier: Modifier = Modifier) {
    val theme = colors
    Row(modifier.paintPanel(Tile, theme.edge, 18).padding(4.dp)
        .horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        RadioTab.entries.filter { it !in state.options.hiddenTabs }.forEach { tab ->
            val selected = tab == state.tab
            Box(Modifier.height(48.dp).paintPanel(if (selected) theme.accent else Color.Transparent, Color.Transparent, 14)
                .clickable(role = Role.Tab, interactionSource = null, indication = ripple()) { model.chooseTab(tab) }
                .padding(horizontal = when { width >= 1000 -> 17.dp; width >= 900 -> 8.dp; else -> 4.dp }),
                contentAlignment = Alignment.Center) {
                RadioText(stringResource(RadioLabels.tab(tab)), 16,
                    ink = if (selected) theme.onAccent else Muted, medium = true, lines = 1)
            }
        }
    }
}

@Composable
private fun StationPlayer(
    state: RadioViewModel.State, model: RadioViewModel, service: RadioService?, compact: Boolean,
    visible: Boolean, modifier: Modifier,
) {
    val colors = colors
    val playback = state.playback
    val playing = playback.active()
    val station = state.selected
    val locale = LocalConfiguration.current.locales[0]
    val focus = LocalFocusManager.current
    val canStep = state.stations.size > 1 || (state.stations.size == 1 && state.stations.first() != station)
    Column(modifier.paintPanel(Panel, colors.selectedEdge, if (compact) 18 else 24)
        .padding(start = if (compact) 14.dp else 25.dp, end = if (compact) 14.dp else 25.dp,
            top = if (compact) 13.dp else 26.dp, bottom = if (compact) 14.dp else 27.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val tone = when (playback.status) {
            Playback.Status.LIVE -> colors.accent
            Playback.Status.CONNECTING, Playback.Status.BUFFERING, Playback.Status.RECONNECTING -> Warm
            else -> if (playback.failed()) ErrorInk else Muted
        }
        RadioText(stringResource(PlaybackText.label(playback.status)), 14,
            Modifier.paintPanel(colors.selected, Color.Transparent, 15).padding(horizontal = 12.dp, vertical = 6.dp),
            ink = tone, medium = true)
        if (!compact) Spacer(Modifier.weight(1f))
        PlayerStage(state, service, compact, visible)
        Spacer(Modifier.height(if (compact) 8.dp else 21.dp))
        RadioText(station?.name ?: stringResource(R.string.no_visible_stations), if (compact) 27 else 36,
            Modifier.fillMaxWidth(), medium = true, align = TextAlign.Center, lines = 2)
        Spacer(Modifier.height(4.dp))
        RadioText(station?.displayLabel(locale).orEmpty(), if (compact) 14 else 17, ink = Muted, align = TextAlign.Center)
        playback.track?.let { track ->
            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
            RadioText(track.title, if (compact) 16 else 20, ink = colors.accent, medium = true, align = TextAlign.Center, lines = 2)
            if (track.artist.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                RadioText(track.artist, if (compact) 14 else 16, ink = Muted, align = TextAlign.Center, lines = 1)
            }
        }
        if (!compact) Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(if (compact) 12.dp else 18.dp))
        val button = if (compact) 50.dp else 58.dp
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
            Tool(Kind.PREVIOUS, stringResource(R.string.previous), { focus.clearFocus(); model.step(-1) },
                Modifier.size(button), enabled = canStep)
            Row(Modifier.height(button).widthIn(min = if (compact) 120.dp else 142.dp)
                .alpha(if (station != null) 1f else .42f).paintPanel(colors.accent, Color.Transparent, 16)
                .clickable(role = Role.Button, enabled = station != null, interactionSource = null, indication = ripple()) {
                    focus.clearFocus()
                    model.togglePlayback()
                }.padding(start = 14.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                    Mark(if (playing) Kind.PAUSE else Kind.PLAY, 24, colors.onAccent)
                }
                Spacer(Modifier.width(7.dp))
                RadioText(stringResource(if (playing) R.string.pause else if (playback.failed()) R.string.retry else R.string.play),
                    16, ink = colors.onAccent, medium = true)
            }
            Tool(Kind.NEXT, stringResource(R.string.next), { focus.clearFocus(); model.step(1) },
                Modifier.size(button), enabled = canStep)
        }
    }
}

@Composable
private fun PlayerStage(state: RadioViewModel.State, service: RadioService?, compact: Boolean, visible: Boolean) {
    val theme = colors
    val status = state.playback.status
    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) { started = true; onStopOrDispose { started = false } }
    val fetching = started && visible && status in setOf(Playback.Status.CONNECTING, Playback.Status.BUFFERING, Playback.Status.RECONNECTING)
    val visual = started && visible && status == Playback.Status.LIVE && state.visualization != "off" && service != null
    if (compact && !visual && !fetching) return
    Box(Modifier.fillMaxWidth().height(if (compact) 58.dp else 132.dp), contentAlignment = Alignment.Center) {
        if (fetching) {
            val sample by produceState(0 to 0L, service) {
                while (service != null) {
                    value = service.bufferedAudioMs() to service.receivedDuringBuffering()
                    delay(200)
                }
            }
            val total = if (status == Playback.Status.BUFFERING) RadioService.REBUFFER_MS else RadioService.INITIAL_BUFFER_MS
            val text = if (sample.second == 0L) stringResource(R.string.buffer_amount, sample.first / 1000f)
            else stringResource(R.string.buffer_amount_with_transfer, sample.first / 1000f,
                Formatter.formatShortFileSize(LocalContext.current, sample.second))
            Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 24.dp else 52.dp)) {
                RadioText(text, if (compact) 14 else 17, Modifier.fillMaxWidth(), medium = true, align = TextAlign.Center, lines = 2)
                Spacer(Modifier.height(8.dp))
                Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                    drawRoundRect(theme.selectedEdge, cornerRadius = CornerRadius(size.height / 2))
                    val fill = (sample.first.toFloat() / total).coerceIn(0f, 1f)
                    if (fill > 0) drawRoundRect(theme.accent, size = Size(size.width * fill, size.height),
                        cornerRadius = CornerRadius(size.height / 2))
                }
            }
        } else if (visual) {
            RadioMeter(service!!.meter(), state.visualization == "wave", state.smooth, Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp))
        } else {
            Box(Modifier.size(112.dp).paintPanel(theme.selected, theme.selectedEdge, 62), contentAlignment = Alignment.Center) {
                Mark(Kind.SIGNAL, 88, theme.accent)
            }
        }
    }
}

@Composable
private fun RadioMeter(meter: AudioMeter, wave: Boolean, smooth: Boolean, modifier: Modifier) {
    DisposableEffect(meter) { meter.setEnabled(true); onDispose { meter.setEnabled(false) } }
    val samples by produceState<FloatArray?>(null, meter, wave, smooth) {
        value = null
        var last: AudioMeter.Frame? = null
        while (true) {
            val frame = meter.frame()
            if (frame !== last) {
                val reading = frame?.let { if (wave) it.wave else it.spectrum }
                val previous = value
                value = if (smooth && previous != null && reading != null) FloatArray(reading.size) { index ->
                    previous[index] + (reading[index] - previous[index]) * if (wave || reading[index] > previous[index]) .66f else .28f
                } else reading
                last = frame
            }
            delay(32)
        }
    }
    val ink = colors.accent
    Canvas(modifier) {
        val middle = size.height * .5f
        drawLine(ink.copy(alpha = 42f / 255), Offset(0f, middle), Offset(size.width, middle), 1.5f)
        val signal = samples ?: return@Canvas
        if (wave) {
            val path = Path()
            signal.forEachIndexed { index, value ->
                val x = size.width * index / signal.lastIndex
                val y = middle - (value * 1.6f).coerceIn(-1f, 1f) * size.height * .38f
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, ink, style = Stroke(width = 2.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            val step = size.width / signal.size
            signal.forEachIndexed { index, value ->
                val half = step * .28f
                val x = step * (index + .5f) - half
                val bar = (max(.035f, value) * size.height * .43f).coerceAtLeast(1f)
                drawRoundRect(ink.copy(alpha = 200f / 255), Offset(x, middle - bar), Size(half * 2, bar - 1), CornerRadius(4f))
                drawRoundRect(ink.copy(alpha = 92f / 255), Offset(x, middle + 1), Size(half * 2, bar - 1), CornerRadius(4f))
            }
        }
    }
}

@Composable
private fun Catalog(state: RadioViewModel.State, model: RadioViewModel, modifier: Modifier, columns: Int) {
    val theme = colors
    val focus = LocalFocusManager.current
    val locale = LocalConfiguration.current.locales[0]
    val scroll = rememberLazyGridState()
    var displayedTab by rememberSaveable { mutableStateOf(state.tab.name) }
    var displayedQuery by rememberSaveable { mutableStateOf(state.query) }
    LaunchedEffect(state.tab, state.query) {
        if (state.tab.name != displayedTab || state.query != displayedQuery) {
            scroll.scrollToItem(0)
            displayedTab = state.tab.name
            displayedQuery = state.query
        }
    }
    Column(modifier) {
        BasicTextField(state.query, model::search, Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            cursorBrush = SolidColor(theme.accent), textStyle = TextStyle(color = TextInk, fontSize = 17.sp,
                platformStyle = PlatformTextStyle(includeFontPadding = false)), decorationBox = { input ->
                Row(Modifier.fillMaxWidth().height(54.dp).paintPanel(Tile, theme.edge, 16).padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { Mark(Kind.SEARCH, 24, Muted) }
                    Spacer(Modifier.width(13.dp))
                    Box(Modifier.weight(1f)) {
                        if (state.query.isEmpty()) RadioText(stringResource(R.string.station_search), 17, ink = Muted)
                        input()
                    }
                    if (state.query.isNotEmpty()) Tool(Kind.CLEAR, stringResource(R.string.clear_search), { model.search("") },
                        Modifier.size(48.dp), glyph = 22, tint = Muted, fill = Color.Transparent, edge = Color.Transparent)
                }
            })
        Spacer(Modifier.height(12.dp))
        if (state.stations.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                RadioText(stringResource(when {
                    state.query.isNotBlank() -> R.string.no_results
                    state.tab == RadioTab.FAVORITES && state.favorites.isEmpty() -> R.string.empty_favorites
                    else -> R.string.no_visible_stations
                }), 17, ink = Muted, align = TextAlign.Center)
            }
        } else {
            LazyVerticalGrid(GridCells.Fixed(columns), state = scroll,
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                items(state.stations, key = Station::id) { station ->
                    val selected = station == state.selected
                    val saved = station.id in state.favorites
                    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).paintPanel(if (selected) theme.selected else Tile,
                        if (selected) theme.selectedEdge else theme.edge, 14), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).heightIn(min = 68.dp).clickable(role = Role.Button,
                            indication = ripple(), interactionSource = null) { focus.clearFocus(); model.select(station) }
                            .padding(start = 14.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                            verticalArrangement = Arrangement.Center) {
                            RadioText(station.name, 18, ink = if (selected) theme.accent else TextInk, medium = true, lines = 1)
                            RadioText(if (station.id in state.options.unavailableStations) stringResource(R.string.station_unavailable)
                                else station.displayLabel(locale), 14, ink = Muted, lines = 1)
                        }
                        Tool(if (saved) Kind.STAR_FILLED else Kind.STAR,
                            stringResource(if (saved) R.string.remove_favorite else R.string.add_favorite, station.name),
                            { model.toggleFavorite(station) }, Modifier.padding(end = 5.dp).size(54.dp),
                            glyph = 24, fill = Color.Transparent, edge = Color.Transparent,
                            tint = if (saved) theme.accent else Muted)
                    }
                }
            }
        }
    }
}
