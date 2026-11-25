package app.voyahradio.presentation

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.voyahradio.R
import app.voyahradio.domain.RadioTab
import app.voyahradio.domain.Station
import app.voyahradio.ui.*
import app.voyahradio.ui.RadioGlyph.Kind

@Composable
internal fun RadioSettings(state: RadioViewModel.State, model: RadioViewModel) {
    val wide = LocalConfiguration.current.screenWidthDp >= 760
    val start = if (wide) 30.dp else 16.dp
    val end = if (wide) 26.dp else 16.dp
    Column(Modifier.fillMaxSize().background(Background).padding(start = start, top = 16.dp, end = end, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Tool(Kind.BACK, stringResource(R.string.back), { model.openSettings(false) }, Modifier.size(50.dp), glyph = 22)
            Spacer(Modifier.width(13.dp))
            RadioText(stringResource(R.string.settings), 26, medium = true)
        }
        Spacer(Modifier.height(16.dp))
        if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.width(378.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Appearance(state, model, true)
                    VisibleTabs(state, model, true)
                    Availability(state, model)
                }
                StationSettings(state, model, Modifier.weight(1f).fillMaxHeight(), true)
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Appearance(state, model, false)
                VisibleTabs(state, model, false)
                Availability(state, model)
                StationSettings(state, model, Modifier.fillMaxWidth(), false)
            }
        }
    }
}

@Composable
private fun SettingsCard(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().paintPanel(Panel, colors.edge, 18).padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 8.dp)) {
        RadioText(stringResource(title), 19, medium = true)
        content()
    }
}

@Composable
private fun Appearance(state: RadioViewModel.State, model: RadioViewModel, wide: Boolean) {
    SettingsCard(R.string.appearance) {
        RadioText(stringResource(R.string.language), 14, Modifier.padding(vertical = 7.dp), ink = Muted)
        SettingOptions(state.language, listOf("ru" to R.string.language_ru, "en" to R.string.language_en,
            "kk" to R.string.language_kk), model::language)
        Spacer(Modifier.height(8.dp))
        ChoiceLine(R.string.theme, wide) {
            SettingOptions(state.theme, listOf("graphite" to R.string.theme_graphite,
                "amber" to R.string.theme_amber), model::theme)
        }
        ChoiceLine(R.string.visualization, wide) {
            SettingOptions(state.visualization, listOf("spectrum" to R.string.spectrum, "wave" to R.string.wave,
                "off" to R.string.visualization_off), model::visualization)
        }
        SettingToggle(stringResource(R.string.smooth_visualization), state.smooth, model::smooth)
    }
}

@Composable
private fun ChoiceLine(title: Int, inline: Boolean, content: @Composable () -> Unit) {
    Spacer(Modifier.height(6.dp))
    if (inline) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioText(stringResource(title), 14, Modifier.width(108.dp), ink = Muted)
            Box(Modifier.weight(1f)) { content() }
        }
    } else {
        RadioText(stringResource(title), 14, Modifier.padding(vertical = 7.dp), ink = Muted)
        content()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun <T> SettingOptions(selected: T, options: List<Pair<T, Int>>, change: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        options.forEach { (value, label) ->
            SettingButton(stringResource(label), { change(value) }, Modifier.weight(1f), selected == value)
        }
    }
}

@Composable
private fun SettingButton(label: String, click: () -> Unit, modifier: Modifier, selected: Boolean = false) {
    val tone = colors
    Box(modifier.heightIn(min = 48.dp).paintPanel(if (selected) tone.accent else Tile, tone.edge, 12)
        .clickable(role = Role.Button, indication = ripple(), interactionSource = null, onClick = click)
        .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        RadioText(label, 15, ink = if (selected) tone.onAccent else TextInk, medium = true, align = TextAlign.Center, lines = 2)
    }
}

@Composable
private fun VisibleTabs(state: RadioViewModel.State, model: RadioViewModel, wide: Boolean) {
    SettingsCard(R.string.visible_tabs) {
        RadioTab.entries.chunked(if (wide) 2 else 1).forEach { tabs ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                tabs.forEach { tab ->
                    SettingToggle(stringResource(RadioLabels.tab(tab)), tab !in state.options.hiddenTabs,
                        { model.showTab(tab, it) }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Availability(state: RadioViewModel.State, model: RadioViewModel) {
    SettingsCard(R.string.availability) {
        SettingToggle(stringResource(R.string.hide_unavailable), state.options.hideUnavailable, model::hideUnavailable)
        RadioText(when (state.scan) {
            RadioViewModel.Scan.IDLE -> stringResource(R.string.availability_hint)
            RadioViewModel.Scan.RUNNING -> stringResource(R.string.checking_stations, state.checked, state.total)
            RadioViewModel.Scan.DONE -> stringResource(R.string.check_finished, state.unavailable, state.unknown)
            RadioViewModel.Scan.NO_NETWORK -> stringResource(R.string.check_needs_internet)
        }, 14, ink = Muted)
        SettingButton(stringResource(if (state.scan == RadioViewModel.Scan.RUNNING) R.string.cancel_check
            else R.string.check_availability), model::toggleScan, Modifier.widthIn(min = 142.dp))
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, change: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically) {
        RadioText(label, 16, Modifier.weight(1f))
        SmallSwitch(label, checked, change, Modifier.size(34.dp, 30.dp), false)
    }
}

@Composable
private fun SmallSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier, clickable: Boolean = true) {
    val theme = colors
    val thumb by animateFloatAsState(if (checked) 1f else 0f, tween(250), label = "Switch thumb")
    val action = if (clickable) Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .semantics { contentDescription = label } else Modifier
    Canvas(modifier.then(action)) {
        val right = size.width - 12.dp.toPx()
        val y = size.height / 2
        drawRoundRect(if (checked) Color(0xFF47566D) else Color(0xFF5F6773),
            topLeft = Offset(right - 18.dp.toPx(), y - 7.dp.toPx()),
            size = Size(29.dp.toPx(), 14.dp.toPx()), cornerRadius = CornerRadius(7.dp.toPx()))
        drawCircle(if (checked) theme.accent else Color(0xFFBDBDBD), 10.dp.toPx(),
            Offset(right - (1f - thumb) * 16.dp.toPx(), y))
    }
}

@Composable
private fun StationSettings(state: RadioViewModel.State, model: RadioViewModel, modifier: Modifier, wide: Boolean) {
    Column(modifier.paintPanel(Panel, colors.edge, 20).padding(start = 15.dp, top = 15.dp, end = 15.dp, bottom = 4.dp)) {
        RadioText(stringResource(R.string.station_settings), 21, medium = true)
        RadioText(stringResource(R.string.station_settings_hint), 14,
            Modifier.padding(vertical = 7.dp), ink = Muted)
        SettingOptions(state.group, RadioTab.entries.map { it to RadioLabels.tab(it) }, model::chooseGroup)
        Spacer(Modifier.height(8.dp))
        if (wide) {
            val body: @Composable (RadioTab) -> Unit = { group ->
                val rows = if (group == state.group) state.settingsStations else emptyList()
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (rows.isEmpty() && group == state.group) item { EmptySettings(group) }
                    itemsIndexed(rows, key = { _, station -> station.id }) { index, station ->
                        StationSetting(station, state, model, index, Modifier.animateItem(
                            fadeInSpec = null, fadeOutSpec = null, placementSpec = tween(190)))
                    }
                }
            }
            if (ValueAnimator.areAnimatorsEnabled()) {
                AnimatedContent(state.group, modifier = Modifier.weight(1f), transitionSpec = {
                    (fadeIn(tween(170)) + slideInHorizontally(tween(170)) { if (targetState.ordinal > initialState.ordinal) 12 else -12 })
                        .togetherWith(fadeOut(tween(80)))
                }, label = "Station settings tab", content = { body(it) })
            } else Box(Modifier.weight(1f)) { body(state.group) }
        } else {
            if (state.settingsStations.isEmpty()) EmptySettings(state.group)
            state.settingsStations.forEachIndexed { index, station ->
                if (index != 0) Spacer(Modifier.height(6.dp))
                StationSetting(station, state, model, index)
            }
        }
    }
}

@Composable
private fun EmptySettings(group: RadioTab) {
    RadioText(stringResource(if (group == RadioTab.FAVORITES) R.string.empty_favorites
        else R.string.no_visible_stations), 14, Modifier.padding(8.dp), ink = Muted)
}

@Composable
private fun StationSetting(
    station: Station, state: RadioViewModel.State, model: RadioViewModel, index: Int, modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 60.dp).paintPanel(Tile, colors.edge, 13)
        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            RadioText(station.name, 17, medium = true, lines = 1)
            RadioText(if (station.id in state.options.unavailableStations) stringResource(R.string.station_unavailable)
                else station.displayLabel(LocalConfiguration.current.locales[0]), 14, ink = Muted, lines = 1)
        }
        SmallSwitch(stringResource(R.string.show_station, station.name), station.id !in state.options.hiddenStations,
            { model.showStation(station, it) }, Modifier.size(38.dp, 48.dp))
        Tool(Kind.UP, stringResource(R.string.move_station_up, station.name),
            { model.move(station, -1) }, glyph = 22, enabled = index > 0)
        Spacer(Modifier.width(4.dp))
        Tool(Kind.DOWN, stringResource(R.string.move_station_down, station.name),
            { model.move(station, 1) }, glyph = 22, enabled = index < state.settingsStations.lastIndex)
    }
}
