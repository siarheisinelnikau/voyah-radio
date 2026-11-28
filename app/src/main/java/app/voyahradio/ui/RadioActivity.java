package app.voyahradio.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.InputType;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import app.voyahradio.R;
import app.voyahradio.RadioApp;
import app.voyahradio.data.RadioPreferences;
import app.voyahradio.domain.Playback;
import app.voyahradio.domain.RadioTab;
import app.voyahradio.domain.Station;
import app.voyahradio.domain.Track;
import app.voyahradio.playback.PlaybackText;
import app.voyahradio.playback.RadioService;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static app.voyahradio.ui.RadioGlyph.Kind.*;

public final class RadioActivity extends Activity {
    private static final long BUFFER_REFRESH_MS = 200;
    /** The search button is as large as the settings button; it opens into the field over this time. */
    private static final int SEARCH_BUTTON_DP = 52;
    private static final long SEARCH_MOTION_MS = 250;

    private final EnumMap<RadioTab, TextView> tabs = new EnumMap<>(RadioTab.class);

    private RadioViewModel model;
    private final Consumer<Playback> playbackListener = state -> model.onPlayback(state);
    private RadioService service;
    private boolean bound;
    private boolean uiStarted;
    private boolean collapsibleStage;
    private String displayedTheme;
    private int listColumns;
    private TextView stationTitle;
    private TextView stationLabel;
    private TextView trackTitle;
    private TextView trackArtist;
    private FrameLayout visualStage;
    private RadioGlyph signal;
    private RadioVisualizer visualizer;
    private LinearLayout bufferPanel;
    private ProgressBar bufferProgress;
    private TextView bufferAmount;
    private final Runnable bufferTick = this::refreshBufferProgress;
    private TextView playbackStatus;
    private LinearLayout playButton;
    private TextView playLabel;
    private RadioGlyph playIcon;
    private View previous;
    private View next;
    private EditText search;
    private View clear;
    private ListView stationList;
    private StationAdapter stationAdapter;
    private TextView emptyMessage;
    private RadioTab displayedTab;
    private Set<RadioTab> displayedHiddenTabs = Collections.emptySet();
    private String displayedQuery;
    private String displayedStation;
    private View searchSpace;
    private HorizontalScrollView tabScroll;
    private FrameLayout searchBox;
    private ValueAnimator searchMotion;
    /** How far the search has opened, 0 for the button and 1 for the field. */
    private float searchOpen;
    private int searchWidth;
    private boolean searchExpanded;
    private boolean touchingList;
    private int listScrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
    /** The list scrolls because it follows the station, not because of the user. */
    private boolean ownScroll;
    private int pendingRow = -1;
    private boolean pendingSmooth;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RadioService.LocalBinder) binder).service();
            service.setWindowFocused(hasWindowFocus());
            service.refreshLanguage();
            service.observe(playbackListener);
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            service = null;
            Station selected = model.state().selected;
            model.onPlayback(new Playback(selected == null ? "" : selected.id, Playback.Status.READY));
        }
    };

    @Override protected void attachBaseContext(Context base) {
        Context localized = RadioLocale.apply(base, new RadioPreferences(base));
        super.attachBaseContext(ScreenScale.apply(localized));
    }

    @Override protected void onCreate(Bundle saved) {
        displayedTheme = new RadioPreferences(this).theme();
        RadioTheme.apply(this, displayedTheme);
        super.onCreate(saved);
        app.voyahradio.update.Updates.get(this).startup();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        RadioApp app = (RadioApp) getApplication();
        RadioTab tab = saved == null ? app.preferences().lastTab()
                : RadioTab.parse(saved.getString("tab"), RadioTab.BELARUS);
        String query = saved == null ? "" : saved.getString("query", "");
        model = new RadioViewModel(app.catalog(), app.preferences(), new RadioViewModel.Controls() {
            @Override public void play(String id, RadioTab tab) {
                hideKeyboard();
                try {
                    startForegroundService(new Intent(RadioActivity.this, RadioService.class)
                            .setAction(RadioService.PLAY).putExtra(RadioService.STATION_ID, id)
                            .putExtra(RadioService.TAB, tab.name()));
                } catch (IllegalStateException | SecurityException error) {
                    Toast.makeText(RadioActivity.this, R.string.stream_error, Toast.LENGTH_SHORT).show();
                }
            }

            @Override public void pause() {
                if (service != null) service.pause();
            }
        }, tab, query, this::render);

        buildLayout();
        search.setText(query);
        if (!query.isEmpty()) {
            // A search survives a recreated screen open, as it was.
            searchExpanded = true;
            searchBox.post(() -> {
                searchWidth = expandedSearchWidth();
                sizeField();
                showSearch(1f);
            });
        }
        render(model.state());
        // Opening the radio, not coming back to it, starts the last station; a paused radio stays paused on return.
        if (saved == null && app.preferences().playOnOpen()) model.playLast();
    }

    @Override protected void onStart() {
        super.onStart();
        RadioIntro.onActivityStart(this, ((RadioApp) getApplication()).preferences().startupAnimation());
        uiStarted = true;
        bound = bindService(new Intent(this, RadioService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onResume() {
        super.onResume();
        RadioPreferences preferences = ((RadioApp) getApplication()).preferences();
        String displayed = getResources().getConfiguration().getLocales().get(0).getLanguage();
        if (!displayed.equals(preferences.language()) || !displayedTheme.equals(preferences.theme())) {
            recreate();
            return;
        }
        model.refresh();
        if (service != null) service.refreshLanguage();
    }

    @Override protected void onStop() {
        uiStarted = false;
        visualizer.update(null, "off", false, false);
        bufferPanel.setVisibility(View.GONE);
        bufferPanel.removeCallbacks(bufferTick);
        if (service != null) {
            service.setWindowFocused(false);
            service.removeObserver(playbackListener);
        }
        service = null;
        if (bound) unbindService(connection);
        bound = false;
        RadioIntro.onActivityStop(this);
        super.onStop();
    }

    @Override public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        // Settings and other screens opened from here are part of the radio, so coming back skips the intro.
        RadioIntro.onOwnScreenOpened();
        super.startActivityForResult(intent, requestCode, options);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (service != null) service.setWindowFocused(hasFocus);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        RadioViewModel.State state = model.state();
        out.putString("tab", state.tab.name());
        out.putString("query", state.query);
        super.onSaveInstanceState(out);
    }

    private void buildLayout() {
        int width = getResources().getConfiguration().screenWidthDp;
        int height = getResources().getConfiguration().screenHeightDp;
        boolean wide = width >= 720 && width * 2 >= height * 3;
        listColumns = width >= 1000 ? 2 : 1;

        LinearLayout page = vertical();
        page.setBackgroundColor(getColor(R.color.radio_background));
        page.setFocusableInTouchMode(true);
        int outer = wide ? 28 : 16;
        page.setPadding(dp(outer), dp(wide ? 16 : 12), dp(outer), dp(outer > 16 ? 18 : 12));
        setContentView(page);
        page.requestFocus();

        LinearLayout content = wide ? horizontal() : vertical();
        page.addView(content, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

        boolean compactPlayer = !wide || height < 510;
        int playerWidth = Math.min(440, Math.max(292, width * 34 / 100));
        LinearLayout.LayoutParams playerParams = wide ? size(playerWidth, MATCH_PARENT) : wrap(MATCH_PARENT);
        content.addView(playerPanel(compactPlayer, wide), playerParams);

        int catalogWidth = width - 2 * outer - (wide ? playerWidth + 20 : 0);
        LinearLayout.LayoutParams catalogParams = wide
                ? new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f);
        if (wide) catalogParams.leftMargin = dp(20);
        else catalogParams.topMargin = dp(12);
        content.addView(catalogPanel(catalogWidth, !wide), catalogParams);
    }

    private void addCatalogHeader(LinearLayout catalog, int catalogWidth, boolean stacked) {
        boolean singleRow = catalogWidth >= 700;
        LinearLayout actions = horizontal();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        catalog.addView(actions, wrap(MATCH_PARENT));

        LinearLayout tabGroup = horizontal();
        tabGroup.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabGroup.setBackground(shape(getColor(R.color.radio_surface), 18, themeColor(R.attr.radio_edge_role)));
        if (stacked) {
            tabScroll = new HorizontalScrollView(this);
            tabScroll.setHorizontalScrollBarEnabled(false);
            tabScroll.addView(tabGroup, new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
            actions.addView(tabScroll, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
            searchSpace = tabScroll;
        } else if (singleRow) {
            actions.addView(tabGroup, wrap(WRAP_CONTENT));
        } else {
            LinearLayout.LayoutParams tabParams = wrap(MATCH_PARENT);
            tabParams.topMargin = dp(8);
            catalog.addView(tabGroup, tabParams);
        }
        int tabPadding = catalogWidth >= 800 ? 17 : 6;
        for (RadioTab tab : RadioTab.values()) addTab(tabGroup, tab, singleRow || stacked, tabPadding);

        if (!stacked) {
            searchSpace = new View(this);
            actions.addView(searchSpace, new LinearLayout.LayoutParams(0, 1, 1f));
        }
        LinearLayout.LayoutParams searchParams = size(SEARCH_BUTTON_DP, SEARCH_BUTTON_DP);
        searchParams.leftMargin = dp(8);
        actions.addView(searchBox(), searchParams);

        View settings = iconButton(SETTINGS, getColor(R.color.radio_text), R.string.settings, () -> {
            hideKeyboard();
            startActivity(new Intent(this, RadioSettingsActivity.class));
        });
        settings.setBackground(actionBackground(getColor(R.color.radio_surface), 15, themeColor(R.attr.radio_edge_role)));
        LinearLayout.LayoutParams settingsParams = size(52, 52);
        settingsParams.leftMargin = dp(8);
        actions.addView(settings, settingsParams);
    }

    private void addTab(LinearLayout group, RadioTab tab, boolean singleRow, int padding) {
        TextView button = text(16, getColor(R.color.radio_text), true);
        button.setText(RadioLabels.tab(tab));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setPadding(dp(padding), 0, dp(padding), 0);
        buttonLike(button, null, () -> model.chooseTab(tab));
        LinearLayout.LayoutParams params = singleRow ? wrap(WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        group.addView(button, params);
        tabs.put(tab, button);
    }

    private View playerPanel(boolean compact, boolean wide) {
        LinearLayout panel = vertical();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(compact ? 14 : 25), dp(compact ? 13 : 26),
                dp(compact ? 14 : 25), dp(compact ? 14 : 27));
        panel.setBackground(shape(getColor(R.color.radio_panel), compact ? 18 : 24,
                themeColor(R.attr.radio_selected_edge_role)));

        LinearLayout metadata = panel;
        LinearLayout signalPanel = panel;
        if (!wide) {
            LinearLayout summary = horizontal();
            summary.setGravity(Gravity.CENTER_VERTICAL);
            panel.addView(summary, wrap(MATCH_PARENT));
            metadata = vertical();
            summary.addView(metadata, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 3f));
            signalPanel = vertical();
            LinearLayout.LayoutParams signalParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f);
            signalParams.leftMargin = dp(8);
            summary.addView(signalPanel, signalParams);
        }

        playbackStatus = text(wide ? 14 : 12, themeColor(R.attr.radio_accent_role), true);
        playbackStatus.setGravity(Gravity.CENTER);
        playbackStatus.setPadding(dp(wide ? 12 : 6), dp(6), dp(wide ? 12 : 6), dp(6));
        playbackStatus.setMaxLines(2);
        playbackStatus.setEllipsize(TextUtils.TruncateAt.END);
        playbackStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        // An error message takes two lines; the status keeps room for them so the panel does not move.
        LinearLayout statusSlot = vertical();
        statusSlot.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        if (wide) statusSlot.setMinimumHeight(playbackStatus.getLineHeight() * 2 + dp(12));
        statusSlot.addView(playbackStatus, wrap(WRAP_CONTENT));
        signalPanel.addView(statusSlot, wrap(MATCH_PARENT));

        addVisualStage(signalPanel, compact, wide);

        // Reserve room for metadata so changes to the station or track keep the visualization and controls in place.
        int nameLines = compact ? 1 : 2;
        stationTitle = text(!wide ? 22 : compact ? 27 : 36, getColor(R.color.radio_text), true);
        stationTitle.setGravity(wide ? Gravity.CENTER : Gravity.START);
        stationTitle.setMinLines(nameLines);
        stationTitle.setMaxLines(nameLines);
        stationTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = wrap(MATCH_PARENT);
        titleParams.topMargin = dp(!wide ? 0 : compact ? 8 : 21);
        metadata.addView(stationTitle, titleParams);
        stationLabel = text(compact ? 14 : 17, getColor(R.color.radio_muted), false);
        stationLabel.setGravity(wide ? Gravity.CENTER : Gravity.START);
        stationLabel.setMaxLines(1);
        stationLabel.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = wrap(MATCH_PARENT);
        labelParams.topMargin = dp(4);
        metadata.addView(stationLabel, labelParams);

        // Room for the track title and its artist, held while a station has no track yet or none at all.
        LinearLayout trackBlock = vertical();
        trackBlock.setGravity(wide ? Gravity.CENTER : Gravity.START);
        LinearLayout.LayoutParams trackParams = wrap(MATCH_PARENT);
        trackParams.topMargin = dp(!wide ? 4 : compact ? 8 : 16);
        metadata.addView(trackBlock, trackParams);
        trackTitle = text(compact ? 16 : 20, themeColor(R.attr.radio_accent_role), true);
        trackTitle.setGravity(wide ? Gravity.CENTER : Gravity.START);
        trackTitle.setMaxLines(nameLines);
        trackTitle.setEllipsize(TextUtils.TruncateAt.END);
        trackBlock.addView(trackTitle, wrap(MATCH_PARENT));
        trackArtist = text(compact ? 14 : 16, getColor(R.color.radio_muted), false);
        trackArtist.setGravity(wide ? Gravity.CENTER : Gravity.START);
        trackArtist.setMaxLines(1);
        trackArtist.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams artistParams = wrap(MATCH_PARENT);
        artistParams.topMargin = dp(4);
        trackBlock.addView(trackArtist, artistParams);
        trackBlock.setMinimumHeight(trackTitle.getLineHeight() * nameLines + dp(4) + trackArtist.getLineHeight());

        LinearLayout controls = horizontal();
        controls.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlsParams = wrap(MATCH_PARENT);
        controlsParams.topMargin = dp(!wide ? 8 : compact ? 12 : 18);
        panel.addView(controls, controlsParams);

        int buttonSize = !wide ? 46 : compact ? 50 : 58;
        previous = iconButton(PREVIOUS, getColor(R.color.radio_text), R.string.previous, () -> model.step(-1));
        previous.setBackground(actionBackground(getColor(R.color.radio_surface), 15, themeColor(R.attr.radio_edge_role)));
        controls.addView(previous, size(buttonSize, buttonSize));

        playButton = horizontal();
        playButton.setGravity(Gravity.CENTER);
        playButton.setPadding(dp(14), 0, dp(16), 0);
        playButton.setMinimumWidth(dp(!wide ? 108 : compact ? 120 : 142));
        playButton.setBackground(actionBackground(themeColor(R.attr.radio_accent_role), 16, Color.TRANSPARENT));
        buttonLike(playButton, null, () -> model.togglePlayback());
        playIcon = new RadioGlyph(this, PLAY, themeColor(R.attr.radio_on_accent_role), 24);
        playButton.addView(playIcon, size(26, 26));
        playLabel = text(16, themeColor(R.attr.radio_on_accent_role), true);
        playLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams playText = wrap(WRAP_CONTENT);
        playText.leftMargin = dp(7);
        playButton.addView(playLabel, playText);
        LinearLayout.LayoutParams playParams = size(WRAP_CONTENT, buttonSize);
        playParams.setMargins(dp(9), 0, dp(9), 0);
        controls.addView(playButton, playParams);

        next = iconButton(NEXT, getColor(R.color.radio_text), R.string.next, () -> model.step(1));
        next.setBackground(actionBackground(getColor(R.color.radio_surface), 15, themeColor(R.attr.radio_edge_role)));
        controls.addView(next, size(buttonSize, buttonSize));
        return panel;
    }

    private void addVisualStage(LinearLayout panel, boolean compact, boolean wide) {
        collapsibleStage = !wide;
        visualStage = new FrameLayout(this);
        LinearLayout.LayoutParams stageParams = wide
                ? new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f) : size(MATCH_PARENT, 58);
        panel.addView(visualStage, stageParams);

        int accent = themeColor(R.attr.radio_accent_role);
        signal = new RadioGlyph(this, SIGNAL, accent, 88);
        signal.setBackground(shape(themeColor(R.attr.radio_selected_role), 62,
                themeColor(R.attr.radio_selected_edge_role)));
        visualStage.addView(signal, new FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER));

        visualizer = new RadioVisualizer(this);
        FrameLayout.LayoutParams visualParams = new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
        visualParams.setMargins(dp(8), dp(4), dp(8), dp(4));
        visualStage.addView(visualizer, visualParams);

        bufferPanel = vertical();
        bufferPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        int inset = dp(!wide ? 6 : compact ? 24 : 52);
        bufferPanel.setPadding(inset, 0, inset, 0);
        bufferAmount = text(!wide ? 11 : compact ? 14 : 17, getColor(R.color.radio_text), true);
        bufferAmount.setGravity(Gravity.CENTER);
        bufferAmount.setMaxLines(2);
        bufferAmount.setEllipsize(TextUtils.TruncateAt.END);
        bufferPanel.addView(bufferAmount, wrap(MATCH_PARENT));
        bufferProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bufferProgress.setIndeterminate(false);
        bufferProgress.setProgressTintList(ColorStateList.valueOf(accent));
        bufferProgress.setProgressBackgroundTintList(
                ColorStateList.valueOf(themeColor(R.attr.radio_selected_edge_role)));
        bufferProgress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams progressParams = size(MATCH_PARENT, 8);
        progressParams.topMargin = dp(8);
        bufferPanel.addView(bufferProgress, progressParams);
        visualStage.addView(bufferPanel, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER));
        bufferPanel.setVisibility(View.GONE);
    }

    /**
     * The search: a button like the settings button that grows to the left into the search field. The field closes
     * again on Back, on its clear button and when it loses focus while empty.
     */
    private View searchBox() {
        searchBox = new FrameLayout(this);
        searchBox.setBackground(actionBackground(getColor(R.color.radio_surface), 15, themeColor(R.attr.radio_edge_role)));
        buttonLike(searchBox, getString(R.string.station_search), () -> {
            if (!searchExpanded) expandSearch();
            else showKeyboard();
        });
        searchBox.addView(new RadioGlyph(this, SEARCH, getColor(R.color.radio_text), 25),
                new FrameLayout.LayoutParams(dp(SEARCH_BUTTON_DP), MATCH_PARENT, Gravity.START | Gravity.CENTER_VERTICAL));

        search = new EditText(this);
        search.setSingleLine();
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setHint(R.string.station_search);
        search.setTextColor(getColor(R.color.radio_text));
        search.setHintTextColor(getColor(R.color.radio_muted));
        search.setTextSize(17);
        search.setPadding(dp(5), 0, 0, 0);
        search.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams fieldParams = new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
        fieldParams.leftMargin = dp(SEARCH_BUTTON_DP - 4);
        fieldParams.rightMargin = dp(50);
        searchBox.addView(search, fieldParams);
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            hideKeyboard();
            return true;
        });
        search.setOnFocusChangeListener((view, focused) -> {
            if (!focused && search.length() == 0) collapseSearch(false);
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int before, int count) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                model.search(text.toString());
            }
            @Override public void afterTextChanged(Editable edit) {}
        });
        clear = iconButton(CLEAR, getColor(R.color.radio_muted), R.string.clear_search, () -> collapseSearch(true));
        clear.setBackground(actionBackground(getColor(R.color.radio_surface), 13, Color.TRANSPARENT));
        FrameLayout.LayoutParams clearParams = new FrameLayout.LayoutParams(dp(48), dp(48),
                Gravity.END | Gravity.CENTER_VERTICAL);
        clearParams.rightMargin = dp(2);
        searchBox.addView(clear, clearParams);
        showSearch(0f);
        return searchBox;
    }

    private void expandSearch() {
        searchExpanded = true;
        searchWidth = expandedSearchWidth();
        // The field takes focus and opens the keyboard at once, while it still grows from the button.
        sizeField();
        search.setVisibility(View.VISIBLE);
        moveSearch(1f, true);
        showKeyboard();
    }

    /**
     * Gives the field its open width from the start; the growing button clips it. A field of no width could not take
     * focus.
     */
    private void sizeField() {
        ViewGroup.LayoutParams params = search.getLayoutParams();
        params.width = Math.max(1, searchWidth - dp(SEARCH_BUTTON_DP - 4) - dp(50));
        search.setLayoutParams(params);
    }

    /** Closes the search field, also clearing the search when {@code clearQuery}. */
    private void collapseSearch(boolean clearQuery) {
        if (clearQuery && search.length() > 0) search.setText("");
        if (!searchExpanded) return;
        searchExpanded = false;
        hideKeyboard();
        moveSearch(0f, true);
    }

    private int expandedSearchWidth() {
        return Math.min(dp(360), searchBox.getWidth() + searchSpace.getWidth());
    }

    /**
     * Grows the button into the field ({@code target} 1) or back ({@code target} 0) over {@link #SEARCH_MOTION_MS},
     * decelerating as it opens and accelerating as it closes, so closing plays opening backwards.
     */
    private void moveSearch(float target, boolean animate) {
        if (searchMotion != null) searchMotion.cancel();
        searchMotion = null;
        if (!animate || !ValueAnimator.areAnimatorsEnabled() || searchOpen == target) {
            showSearch(target);
            return;
        }
        ValueAnimator motion = ValueAnimator.ofFloat(searchOpen, target);
        motion.setDuration(Math.round(SEARCH_MOTION_MS * Math.abs(target - searchOpen)));
        motion.setInterpolator(target > searchOpen ? new DecelerateInterpolator() : new AccelerateInterpolator());
        motion.addUpdateListener(animation -> showSearch((float) animation.getAnimatedValue()));
        searchMotion = motion;
        motion.start();
    }

    /** Lays the search out {@code open} of the way from the button to the field. */
    private void showSearch(float open) {
        searchOpen = open;
        ViewGroup.LayoutParams params = searchBox.getLayoutParams();
        int collapsed = dp(SEARCH_BUTTON_DP);
        int width = Math.round(collapsed + (Math.max(searchWidth, collapsed) - collapsed) * open);
        if (params != null && params.width != width) {
            params.width = width;
            searchBox.setLayoutParams(params);
        }
        // The text and the clear button fade in over the last 60 % of the opening.
        float contents = Math.max(0f, Math.min(1f, (open - .4f) / .6f));
        search.setAlpha(contents);
        clear.setAlpha(contents);
        // The field keeps focus and the keyboard while it opens, so it is shown from the first frame.
        search.setVisibility(open > 0f || searchExpanded ? View.VISIBLE : View.INVISIBLE);
        clear.setVisibility(open > 0f ? View.VISIBLE : View.GONE);
        searchBox.setContentDescription(open > 0f || searchExpanded ? null : getString(R.string.station_search));
    }

    private void showKeyboard() {
        search.requestFocus();
        search.post(() -> getSystemService(InputMethodManager.class)
                .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT));
    }

    @Override public void onBackPressed() {
        if (searchExpanded) {
            collapseSearch(true);
            return;
        }
        super.onBackPressed();
    }

    private View catalogPanel(int catalogWidth, boolean stacked) {
        LinearLayout catalog = vertical();
        addCatalogHeader(catalog, catalogWidth, stacked);

        // Remembers a finger on the list, which the list must never be moved under.
        FrameLayout listFrame = new FrameLayout(this) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    touchingList = true;
                    ownScroll = false;
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    touchingList = false;
                }
                return super.dispatchTouchEvent(event);
            }
        };
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f);
        listParams.topMargin = dp(12);
        catalog.addView(listFrame, listParams);
        stationList = new ListView(this);
        stationList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                if (state != SCROLL_STATE_FLING) ownScroll = false;
                listScrollState = state;
            }

            @Override public void onScroll(AbsListView view, int first, int visible, int total) {}
        });
        stationList.getViewTreeObserver().addOnGlobalLayoutListener(this::revealPendingStation);
        stationList.setItemsCanFocus(true);
        stationList.setDivider(null);
        stationList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        stationList.setClipToPadding(false);
        stationList.setPadding(0, 0, 0, dp(6));
        stationAdapter = new StationAdapter();
        stationList.setAdapter(stationAdapter);
        listFrame.addView(stationList, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        emptyMessage = text(18, getColor(R.color.radio_muted), false);
        emptyMessage.setGravity(Gravity.CENTER);
        emptyMessage.setPadding(dp(20), 0, dp(20), 0);
        listFrame.addView(emptyMessage, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        return catalog;
    }

    private void render(RadioViewModel.State state) {
        if (displayedTab != state.tab || !displayedHiddenTabs.equals(state.hiddenTabs)) {
            for (RadioTab tab : RadioTab.values()) {
                TextView button = tabs.get(tab);
                button.setVisibility(state.hiddenTabs.contains(tab) ? View.GONE : View.VISIBLE);
                boolean selected = tab == state.tab;
                button.setSelected(selected);
                button.setTextColor(selected ? themeColor(R.attr.radio_on_accent_role) : getColor(R.color.radio_muted));
                int fill = selected ? themeColor(R.attr.radio_accent_role) : getColor(R.color.radio_surface);
                button.setBackground(actionBackground(fill, 14, Color.TRANSPARENT));
            }
            displayedHiddenTabs = state.hiddenTabs;
            if (tabScroll != null) {
                TextView selected = tabs.get(state.tab);
                selected.post(() -> selected.requestRectangleOnScreen(
                        new Rect(0, 0, selected.getWidth(), selected.getHeight()), true));
            }
        }

        boolean opened = displayedTab == null;
        String station = state.selected == null ? null : state.selected.id;
        stationAdapter.update(state);
        int row = StationFollow.rowOf(state.stations, state.selected, listColumns);
        switch (StationFollow.move(opened, displayedTab != state.tab, !state.query.equals(displayedQuery),
                !Objects.equals(station, displayedStation), row)) {
            case TOP -> stationList.setSelection(0);
            case SHOW_FROM_TOP -> {
                stationList.setSelection(0);
                followStation(row, false);
            }
            case FOLLOW -> followStation(row, true);
            case STAY -> { }
        }
        displayedTab = state.tab;
        displayedQuery = state.query;
        displayedStation = station;

        boolean empty = state.stations.isEmpty();
        emptyMessage.setVisibility(empty ? View.VISIBLE : View.GONE);
        int emptyText = !state.query.trim().isEmpty() ? R.string.no_results
                : state.tab == RadioTab.FAVORITES && state.favorites.isEmpty()
                        ? R.string.empty_favorites : R.string.no_visible_stations;
        emptyMessage.setText(emptyText);

        boolean hasStation = state.selected != null;
        setText(stationTitle, hasStation ? state.selected.name : getString(R.string.no_visible_stations));
        setText(stationLabel, hasStation ? stationDetail(state.selected) : "");
        renderTrack(state.playback);
        renderStage(state.playback);
        setText(playbackStatus, getString(PlaybackText.label(state.playback.status)));
        int statusColor = state.playback.failed() ? getColor(R.color.radio_error) : switch (state.playback.status) {
            case LIVE -> themeColor(R.attr.radio_accent_role);
            case CONNECTING, BUFFERING, RECONNECTING -> getColor(R.color.radio_warm);
            default -> getColor(R.color.radio_muted);
        };
        if (playbackStatus.getCurrentTextColor() != statusColor || playbackStatus.getBackground() == null) {
            playbackStatus.setTextColor(statusColor);
            playbackStatus.setBackground(shape(themeColor(R.attr.radio_selected_role), 30, Color.TRANSPARENT));
        }
        boolean playing = state.playback.active();
        int action = playing ? R.string.pause : (state.playback.failed() ? R.string.retry : R.string.play);
        setText(playLabel, getString(action));
        playButton.setContentDescription(getString(action));
        playButton.setEnabled(hasStation);
        playButton.setAlpha(hasStation ? 1f : 0.42f);
        playIcon.appearance(playing ? PAUSE : PLAY, themeColor(R.attr.radio_on_accent_role));
        boolean canStep = state.stations.size() > 1
                || (state.stations.size() == 1 && state.stations.get(0) != state.selected);
        previous.setEnabled(canStep);
        next.setEnabled(canStep);
        previous.setAlpha(canStep ? 1f : 0.42f);
        next.setAlpha(canStep ? 1f : 0.42f);

    }

    private String stationDetail(Station station) {
        return station.displayLabel(getResources().getConfiguration().getLocales().get(0));
    }

    private void renderTrack(Playback playback) {
        Track track = playback.track;
        trackTitle.setVisibility(track != null ? View.VISIBLE : View.GONE);
        setText(trackTitle, track == null ? "" : track.title);
        boolean showArtist = track != null && !track.artist.isEmpty();
        trackArtist.setVisibility(showArtist ? View.VISIBLE : View.GONE);
        setText(trackArtist, showArtist ? track.artist : "");
    }

    private void renderStage(Playback playback) {
        RadioPreferences preferences = ((RadioApp) getApplication()).preferences();
        String mode = preferences.visualization();
        boolean drawingSound = uiStarted && service != null && playback.status == Playback.Status.LIVE
                && !"off".equals(mode);
        boolean fetching = uiStarted && switch (playback.status) {
            case CONNECTING, BUFFERING, RECONNECTING -> true;
            default -> false;
        };
        // In the stacked layout, collapse the visualization when playback stops to leave room for the station list.
        boolean playing = uiStarted && playback.active();
        visualStage.setVisibility(!collapsibleStage || fetching || drawingSound || playing ? View.VISIBLE : View.GONE);
        signal.setVisibility(!collapsibleStage && !fetching && !drawingSound ? View.VISIBLE : View.GONE);
        boolean bufferWasVisible = bufferPanel.getVisibility() == View.VISIBLE;
        bufferPanel.setVisibility(fetching ? View.VISIBLE : View.GONE);
        if (fetching) {
            bufferProgress.setMax(playback.status == Playback.Status.BUFFERING
                    ? RadioService.REBUFFER_MS : RadioService.INITIAL_BUFFER_MS);
            if (!bufferWasVisible) refreshBufferProgress();
        } else if (bufferWasVisible) {
            bufferPanel.removeCallbacks(bufferTick);
        }
        visualizer.setVisibility(drawingSound ? View.VISIBLE : View.GONE);
        visualizer.update(service == null ? null : service.meter(), mode, drawingSound,
                preferences.smoothVisualization());
    }

    private void refreshBufferProgress() {
        if (!uiStarted || bufferPanel.getVisibility() != View.VISIBLE) return;
        int bufferedMs = service == null ? 0 : service.bufferedAudioMs();
        long received = service == null ? 0 : service.receivedDuringBuffering();
        float bufferedSeconds = bufferedMs / 1000f;
        bufferProgress.setProgress(Math.min(bufferedMs, bufferProgress.getMax()));
        setText(bufferAmount, received == 0 ? getString(R.string.buffer_amount, bufferedSeconds)
                : getString(R.string.buffer_amount_with_transfer, bufferedSeconds,
                        Formatter.formatShortFileSize(this, received)));
        bufferPanel.postDelayed(bufferTick, BUFFER_REFRESH_MS);
    }

    /**
     * Brings the station's row into view with the row above it, once the list has laid out its new rows; a row that is
     * already fully visible stays where it is. {@code smooth} scrolls there, and only while nobody holds or flings
     * the list.
     */
    private void followStation(int row, boolean smooth) {
        pendingRow = row;
        pendingSmooth = smooth;
        if (stationList.isLaidOut() && !stationList.isLayoutRequested()) revealPendingStation();
    }

    private void revealPendingStation() {
        int row = pendingRow;
        if (row < 0) return;
        pendingRow = -1;
        boolean scrolling = listScrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
        if (pendingSmooth && !StationFollow.mayMove(touchingList, scrolling, ownScroll)) return;
        View line = stationList.getChildAt(row - stationList.getFirstVisiblePosition());
        if (line != null && StationFollow.fullyVisible(line.getTop(), line.getBottom() - line.getPaddingBottom(),
                stationList.getHeight())) return;
        int first = StationFollow.firstRow(row);
        if (pendingSmooth) {
            ownScroll = true;
            stationList.smoothScrollToPositionFromTop(first, 0);
        } else {
            stationList.setSelection(first);
        }
    }

    private final class StationAdapter extends BaseAdapter {
        private List<Station> stations = Collections.emptyList();
        private Set<String> favorites = Collections.emptySet();
        private Set<String> unavailable = Collections.emptySet();
        private Station selected;

        void update(RadioViewModel.State state) {
            if (selected == state.selected && stations.equals(state.stations)
                    && favorites.equals(state.favorites) && unavailable.equals(state.unavailable)) return;
            stations = state.stations;
            favorites = state.favorites;
            unavailable = state.unavailable;
            selected = state.selected;
            notifyDataSetChanged();
        }

        @Override public int getCount() {
            return (stations.size() + listColumns - 1) / listColumns;
        }

        @Override public Station getItem(int position) {
            return stations.get(position * listColumns);
        }

        @Override public long getItemId(int position) {
            return position;
        }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            StationLine line = recycled == null ? new StationLine() : (StationLine) recycled.getTag();
            int first = position * listColumns;
            for (int column = 0; column < listColumns; column++) {
                int index = first + column;
                Station station = index < stations.size() ? stations.get(index) : null;
                line.columns[column].bind(station, selected, favorites, unavailable);
            }
            return line.view;
        }
    }

    private final class StationLine {
        final LinearLayout view = horizontal();
        final StationRow[] columns = new StationRow[listColumns];

        StationLine() {
            view.setTag(this);
            view.setPadding(0, 0, 0, dp(8));
            view.setLayoutParams(new AbsListView.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            for (int col = 0; col < listColumns; col++) {
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
                if (col > 0) tileParams.leftMargin = dp(9);
                columns[col] = new StationRow();
                view.addView(columns[col].tile, tileParams);
            }
        }
    }

    private final class StationRow {
        Station station;
        final LinearLayout tile;
        final LinearLayout listen;
        final TextView name;
        final TextView label;
        final RadioGlyph star;
        final FrameLayout favorite;
        Boolean selected;
        Boolean saved;

        StationRow() {
            tile = horizontal();
            tile.setGravity(Gravity.CENTER_VERTICAL);

            listen = vertical();
            listen.setGravity(Gravity.CENTER_VERTICAL);
            listen.setMinimumHeight(dp(68));
            listen.setPadding(dp(14), dp(7), dp(4), dp(7));
            listen.setBackground(actionBackground(Color.TRANSPARENT, 14, Color.TRANSPARENT));
            buttonLike(listen, null, () -> model.select(station));
            tile.addView(listen, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));

            name = text(18, getColor(R.color.radio_text), true);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            listen.addView(name, wrap(MATCH_PARENT));
            label = text(14, getColor(R.color.radio_muted), false);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            listen.addView(label, wrap(MATCH_PARENT));

            favorite = new FrameLayout(RadioActivity.this);
            favorite.setBackground(actionBackground(Color.TRANSPARENT, 14, Color.TRANSPARENT));
            buttonLike(favorite, null, () -> {
                if (station != null) model.toggleFavorite(station);
            });
            star = new RadioGlyph(RadioActivity.this, STAR, getColor(R.color.radio_muted), 24);
            favorite.addView(star, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
            LinearLayout.LayoutParams favoriteParams = size(54, 54);
            favoriteParams.rightMargin = dp(5);
            tile.addView(favorite, favoriteParams);
        }

        void bind(Station station, Station activeStation, Set<String> favorites, Set<String> unavailable) {
            tile.setVisibility(station == null ? View.INVISIBLE : View.VISIBLE);
            if (station == null) return;
            if (this.station != station) {
                this.station = station;
                listen.setContentDescription(station.name);
                setText(name, station.name);
                saved = null;
            }
            boolean active = station == activeStation;
            boolean isFavorite = favorites.contains(station.id);
            setText(label, unavailable.contains(station.id)
                    ? getString(R.string.station_unavailable) : stationDetail(station));
            if (selected == null || selected != active) {
                selected = active;
                int fill = active ? themeColor(R.attr.radio_selected_role) : getColor(R.color.radio_surface);
                int border = themeColor(active ? R.attr.radio_selected_edge_role : R.attr.radio_edge_role);
                tile.setBackground(shape(fill, 14, border));
                listen.setSelected(active);
                name.setTextColor(active ? themeColor(R.attr.radio_accent_role) : getColor(R.color.radio_text));
            }
            if (saved == null || saved != isFavorite) {
                saved = isFavorite;
                favorite.setContentDescription(getString(
                        isFavorite ? R.string.remove_favorite : R.string.add_favorite, station.name));
                int tint = isFavorite ? themeColor(R.attr.radio_accent_role) : getColor(R.color.radio_muted);
                star.appearance(isFavorite ? STAR_FILLED : STAR, tint);
            }
        }
    }

    private void setText(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private void hideKeyboard() {
        if (search != null && search.hasFocus()) {
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.clearFocus();
        }
    }

    private FrameLayout iconButton(RadioGlyph.Kind icon, int color, int label, Runnable action) {
        FrameLayout button = new FrameLayout(this);
        button.addView(new RadioGlyph(this, icon, color, 25),
                new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        buttonLike(button, getString(label), action);
        return button;
    }

    private void buttonLike(View button, String description, Runnable action) {
        button.setContentDescription(description);
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
    }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) background.setStroke(dp(1), stroke);
        return background;
    }

    private RippleDrawable actionBackground(int color, int radius, int stroke) {
        int[][] states = {{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}};
        int accent = themeColor(R.attr.radio_accent_role);
        int onAccent = themeColor(R.attr.radio_on_accent_role);
        int[] colors = color == accent
                ? new int[]{withAlpha(onAccent, 0x55), withAlpha(onAccent, 0x40), withAlpha(onAccent, 0x33)}
                : new int[]{withAlpha(accent, 0x40), withAlpha(accent, 0x44), withAlpha(accent, 0x22)};
        return new RippleDrawable(new ColorStateList(states, colors), shape(color, radius, stroke),
                shape(Color.WHITE, radius, Color.TRANSPARENT));
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private TextView text(int sp, int color, boolean emphasis) {
        TextView view = new TextView(this);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(emphasis ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout vertical() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        return container;
    }

    private LinearLayout horizontal() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.HORIZONTAL);
        return container;
    }

    private LinearLayout.LayoutParams size(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
    }

    private LinearLayout.LayoutParams wrap(int width) {
        return new LinearLayout.LayoutParams(width, WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int themeColor(int role) {
        return RadioTheme.color(this, role);
    }
}
