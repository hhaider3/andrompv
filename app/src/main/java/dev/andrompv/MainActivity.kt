package dev.andrompv

import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.DragEvent
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), SurfaceHolder.Callback {
    internal lateinit var engine: MPVEngine
    internal val queue = PlaybackQueue()
    private lateinit var store: PlayerStore
    private lateinit var torrents: TorrentStream
    private lateinit var previews: SeekPreview
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    // Views
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var topGradient: View
    private lateinit var bottomGradient: View
    private lateinit var welcomeCard: LinearLayout
    private lateinit var headerBar: LinearLayout
    private lateinit var sidebarToggleBtn: TextView
    private lateinit var titleText: TextView
    private lateinit var subtitleMetaRow: LinearLayout
    private lateinit var controlsContainer: LinearLayout
    private lateinit var controlsCard: LinearLayout
    private lateinit var currentTimeLabel: TextView
    private lateinit var durationTimeLabel: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: TextView
    private lateinit var prevButton: TextView
    private lateinit var nextButton: TextView
    private lateinit var skipIntroChip: TextView
    private lateinit var skipOutroChip: TextView
    private lateinit var speedPill: TextView
    private lateinit var muteButton: TextView
    private lateinit var repeatButton: TextView
    private lateinit var fullscreenButton: TextView
    private lateinit var sidebarToggleBottomBtn: TextView
    private lateinit var sidebarScrim: View
    private lateinit var queueSidebar: LinearLayout
    private lateinit var queueSummaryText: TextView
    private lateinit var queueScroll: ScrollView
    private lateinit var queueRows: LinearLayout
    private lateinit var nowPlayingCard: LinearLayout
    private lateinit var nowPlayingTitle: TextView
    private lateinit var nowPlayingPills: LinearLayout
    private lateinit var nowPlayingStatus: TextView
    private lateinit var previewPopover: LinearLayout
    private lateinit var previewImage: ImageView
    private lateinit var previewLabel: TextView
    private lateinit var loadingBadge: FrameLayout
    private lateinit var errorToast: LinearLayout
    private lateinit var errorToastText: TextView
    private lateinit var gestureHud: TextView
    private lateinit var subtitleSheetOverlay: FrameLayout
    private lateinit var subtitleSheetCard: LinearLayout
    private lateinit var leftCluster: LinearLayout
    private lateinit var centerCluster: LinearLayout
    private lateinit var rightCluster: LinearLayout
    private lateinit var wideControlsRow: LinearLayout
    private lateinit var narrowControls: LinearLayout
    private lateinit var narrowTransportRow: LinearLayout
    private lateinit var toolsRow: LinearLayout
    private var controlsUseNarrowLayout: Boolean? = null

    // State
    private var volume = 80.0
    private var speed = 1.0
    private var muted = false
    private var fullscreen = false
    private var controlsVisible = true
    private var queueVisible = false
    private var subtitleSheetVisible = false
    private var scrubbing = false
    private var wasPlaying = false
    private var surfaceReady = false
    private var pendingPlay: QueueItem? = null
    private var pendingScreenshot: Bitmap? = null
    private var requestGeneration = 0
    private var lastSaved = -1.0
    private var lastPreviewSeek = 0L
    private var foreground = false
    private var destroyed = false
    private var resolvedSource: String? = null
    private var errorMessage: String? = null
    private val collapsedGroups = mutableSetOf<String>()

    // Inset tracking
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0

    // Palette matching macmpv dark glass aesthetic
    private val bgDeep = Color.rgb(6, 7, 12)
    private val glassBg = Color.argb(218, 17, 20, 31)
    private val glassSidebarBg = Color.argb(235, 15, 18, 28)
    private val glassBorder = Color.argb(38, 255, 255, 255)
    private val glassPillBg = Color.argb(26, 255, 255, 255)
    private val accentCyan = Color.rgb(102, 226, 203)
    private val accentIndigo = Color.rgb(99, 102, 241)
    private val textPrimary = Color.WHITE
    private val textSecondary = Color.argb(165, 255, 255, 255)
    private val textMuted = Color.argb(115, 255, 255, 255)

    private val autoHideControls = Runnable {
        if (!destroyed && engine.hasFile && !engine.paused && !scrubbing && !queueVisible && !subtitleSheetVisible) {
            setControlsOverlayVisible(false)
        }
    }
    private val hideGestureHud = Runnable {
        if (!destroyed && ::gestureHud.isInitialized) {
            gestureHud.animate().alpha(0f).setDuration(180).withEndAction { gestureHud.visibility = View.GONE }.start()
        }
    }
    private val ticker = object : Runnable {
        override fun run() {
            if (destroyed) return
            renderPlayback()
            if (foreground) main.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (android.os.Build.VERSION.SDK_INT >= 30)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        }

        store = PlayerStore(this)
        store.restore(queue)
        volume = store.volume
        queueVisible = store.sidebarVisible
        engine = MPVEngine(this)
        torrents = TorrentStream(this)
        previews = SeekPreview(this)
        io.execute { clearCachedSubtitles() }

        engine.onLoaded = {
            engine.set("speed", speed)
            engine.applySubtitles(store.style)
            engine.set("volume", if (muted) 0.0 else volume)
            val current = queue.current
            if (current != null) {
                val meta = engine.currentMediaMetadata()
                if (meta != null) {
                    queue.updateMetadata(current.id, meta)
                    store.save(queue)
                }
            }
            renderPlayback()
            renderQueue()
            clearance()
        }
        engine.onChanged = {
            val current = queue.current
            if (current != null && engine.hasFile && current.metadata?.width == null) {
                val meta = engine.currentMediaMetadata()
                if (meta != null && (meta.width != null || meta.videoCodec != null)) {
                    queue.updateMetadata(current.id, meta)
                }
            }
            renderPlayback()
        }
        engine.onFocusRestored = { engine.set("volume", if (muted) 0.0 else volume) }
        engine.onEof = {
            queue.current?.let(store::finished)
            val next = queue.next(automatic = true)
            if (next != null) play(next, resume = false) else renderPlayback()
        }
        engine.onError = { showError(it) }

        buildUi()
        syncOrientationMode(resources.configuration)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    subtitleSheetVisible -> showSubtitleSheet(false)
                    queueVisible -> setSidebarVisible(false)
                    fullscreen -> setFullscreenMode(false)
                    else -> finish()
                }
            }
        })
        if (savedInstanceState == null) handleIntent(intent)
        probeUnprobedQueueItems()
    }

    private fun isLandscape(config: Configuration = resources.configuration): Boolean =
        config.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun dp(value: Int) = (resources.displayMetrics.density * value).roundToInt()
    private fun dpF(value: Float) = resources.displayMetrics.density * value

    private fun glassDrawable(
        fillColor: Int = glassBg,
        strokeColor: Int = glassBorder,
        radiusDp: Float = 20f,
        strokeWidthDp: Float = 1f
    ) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dpF(radiusDp)
        setColor(fillColor)
        setStroke(dp(strokeWidthDp.roundToInt().coerceAtLeast(1)), strokeColor)
    }

    private fun pillDrawable(fillColor: Int = glassPillBg, strokeColor: Int = Color.TRANSPARENT, radiusDp: Float = 999f): RippleDrawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(radiusDp)
            setColor(fillColor)
            if (strokeColor != Color.TRANSPARENT) setStroke(dp(1), strokeColor)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(radiusDp)
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(Color.argb(48, 255, 255, 255)), content, mask)
    }

    private fun iconButton(
        symbol: String,
        description: String,
        sizeDp: Int = 38,
        textSizeSp: Float = 14f,
        bgColor: Int = Color.argb(24, 255, 255, 255),
        fgColor: Int = textPrimary,
        action: () -> Unit
    ) = TextView(this).apply {
        text = symbol
        contentDescription = description
        textSize = textSizeSp
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(fgColor)
        gravity = Gravity.CENTER
        background = pillDrawable(bgColor)
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setOnClickListener {
            safely(action)
            scheduleHide()
        }
    }

    private fun pillActionButton(
        label: String,
        description: String = label,
        bgColor: Int = Color.argb(32, 255, 255, 255),
        fgColor: Int = textPrimary,
        heightDp: Int = 34,
        horizontalPadDp: Int = 12,
        action: () -> Unit
    ) = TextView(this).apply {
        text = label
        contentDescription = description
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(fgColor)
        gravity = Gravity.CENTER
        background = pillDrawable(bgColor, glassBorder)
        setPadding(dp(horizontalPadDp), 0, dp(horizontalPadDp), 0)
        minHeight = dp(heightDp)
        minimumHeight = dp(heightDp)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            safely(action)
            scheduleHide()
        }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(bgDeep)
            clipChildren = false
            clipToPadding = false
        }

        // 1. Full-bleed video surface (100% width & height of window in both orientations)
        surface = object : SurfaceView(this) {
            override fun performClick(): Boolean {
                super.performClick()
                return true
            }
        }
        surface.holder.addCallback(this)
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Subtle top and bottom scrims when controls are visible
        topGradient = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(175, 0, 0, 0), Color.TRANSPARENT)
            )
            isClickable = false
        }
        root.addView(topGradient, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(104), Gravity.TOP))

        bottomGradient = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(190, 0, 0, 0), Color.TRANSPARENT)
            )
            isClickable = false
        }
        root.addView(bottomGradient, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190), Gravity.BOTTOM))

        // 2. Welcome empty state (macmpv WelcomeView style)
        welcomeCard = buildWelcomeCard()
        root.addView(
            welcomeCard,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        // 3. Seek preview floating popover (positioned dynamically over the seekbar thumb)
        previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        previewLabel = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        previewPopover = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = glassDrawable(Color.argb(238, 18, 21, 30), Color.argb(65, 255, 255, 255), radiusDp = 12f)
            elevation = dpF(10f)
            visibility = View.GONE
            tag = "preview"
            isClickable = false
            addView(previewImage, LinearLayout.LayoutParams(dp(168), dp(94)).apply {
                setMargins(dp(3), dp(3), dp(3), 0)
            })
            addView(previewLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)))
        }
        root.addView(
            previewPopover,
            FrameLayout.LayoutParams(dp(174), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
        )

        // 4. Floating Header Bar (Top overlay)
        headerBar = buildHeaderBar()
        root.addView(
            headerBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )

        // 5. Floating Glass PlayerControls Bar (Bottom overlay)
        controlsContainer = buildControlsBar()
        root.addView(
            controlsContainer,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
        controlsContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            clearance()
            if (bottom - top != oldBottom - oldTop) updateSidebarBottomMargin()
        }

        // 6. Loading spinner & Gesture HUD & Error Toast
        val progress = ProgressBar(this).apply {
            indeterminateTintList = ColorStateList.valueOf(Color.WHITE)
        }
        loadingBadge = FrameLayout(this).apply {
            background = glassDrawable(Color.argb(190, 15, 18, 28), glassBorder, radiusDp = 999f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
            addView(progress, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }
        root.addView(loadingBadge, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))

        gestureHud = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = glassDrawable(Color.argb(215, 15, 18, 28), glassBorder, radiusDp = 999f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            elevation = dpF(8f)
            visibility = View.GONE
        }
        root.addView(
            gestureHud,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        errorToast = buildErrorToast()
        root.addView(
            errorToast,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        )

        // 7. Scrim + Floating Queue Sidebar Overlay (left drawer matching macmpv QueueSidebar)
        sidebarScrim = View(this).apply {
            setBackgroundColor(Color.argb(95, 0, 0, 0))
            visibility = View.GONE
            setOnClickListener { setSidebarVisible(false) }
        }
        root.addView(sidebarScrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        queueSidebar = buildQueueSidebar()
        root.addView(
            queueSidebar,
            FrameLayout.LayoutParams(dp(310), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        )

        // 8. Live Subtitle Appearance & Sync Overlay Sheet
        subtitleSheetOverlay = buildSubtitleSheet()
        root.addView(
            subtitleSheetOverlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // 9. Touch gestures on video surface (single tap, double tap left/center/right, vertical swipe for volume)
        attachSurfaceGestures()

        // 10. Window Insets (safe areas for notches, status bar, gesture bar in both landscape & portrait)
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val mask = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                val bars = insets.getInsetsIgnoringVisibility(mask)
                val visibleBars = insets.getInsets(mask)
                insetLeft = maxOf(bars.left, visibleBars.left)
                insetTop = if (fullscreen) visibleBars.top else maxOf(bars.top, visibleBars.top)
                insetRight = maxOf(bars.right, visibleBars.right)
                insetBottom = if (fullscreen) visibleBars.bottom else maxOf(bars.bottom, visibleBars.bottom)
            } else {
                @Suppress("DEPRECATION")
                insetLeft = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                insetTop = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                insetRight = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                insetBottom = insets.systemWindowInsetBottom
            }
            applyResponsiveLayout()
            insets
        }

        setContentView(root)
        applyResponsiveLayout()
        renderQueue()
        renderPlayback()
    }

    private fun buildWelcomeCard(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }

        // Glowing play badge
        val orb = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.argb(200, 99, 102, 241), Color.argb(150, 34, 211, 238))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), Color.argb(80, 255, 255, 255))
            }
            elevation = dpF(12f)
        }
        val playIcon = TextView(this).apply {
            text = "▶"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            translationX = dpF(2f)
        }
        orb.addView(playIcon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(orb, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(16) })

        val headline = TextView(this).apply {
            text = "Play something beautiful"
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
        }
        container.addView(headline)

        val subhead = TextView(this).apply {
            text = "Open a local video or audio file, playlist, torrent, or network stream."
            textSize = 13f
            setTextColor(textSecondary)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(18))
        }
        container.addView(subhead)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val openMediaBtn = TextView(this).apply {
            text = "＋  Open Media"
            contentDescription = "Open media"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.rgb(12, 14, 22))
            gravity = Gravity.CENTER
            background = pillDrawable(Color.WHITE)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { safely { pickMedia() } }
        }
        val openUrlBtn = TextView(this).apply {
            text = "🔗  Open Stream"
            contentDescription = "Open stream"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(textPrimary)
            gravity = Gravity.CENTER
            background = pillDrawable(Color.argb(34, 255, 255, 255), glassBorder)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { safely { openUrlDialog() } }
        }
        actions.addView(openMediaBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(10)
        })
        actions.addView(openUrlBtn)
        container.addView(actions)
        return container
    }

    private fun buildHeaderBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        sidebarToggleBtn = iconButton("☰", "Queue", sizeDp = 36, textSizeSp = 15f, bgColor = Color.argb(115, 10, 12, 18)) {
            setSidebarVisible(!queueVisible)
        }
        bar.addView(sidebarToggleBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleText = TextView(this).apply {
            text = "andrompv"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(textPrimary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleMetaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleCol.addView(titleText)
        titleCol.addView(subtitleMetaRow)
        bar.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(10) })

        val openBtn = pillActionButton(
            label = "＋ Open",
            description = "Open media files",
            bgColor = Color.argb(115, 10, 12, 18),
            heightDp = 34,
            horizontalPadDp = 12
        ) { pickMedia() }
        bar.addView(openBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(8) })

        val urlBtn = pillActionButton(
            label = "URL",
            description = "Open network URL",
            bgColor = Color.argb(115, 10, 12, 18),
            heightDp = 34,
            horizontalPadDp = 12
        ) { openUrlDialog() }
        bar.addView(urlBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply { marginEnd = dp(8) })

        val moreBtn = iconButton("⋮", "Player options", sizeDp = 34, textSizeSp = 16f, bgColor = Color.argb(115, 10, 12, 18)) {
            showOptions()
        }
        bar.addView(moreBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

        return bar
    }

    private fun buildControlsBar(): LinearLayout {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        // Quick action chips above the glass bar for Skip Intro / Skip Outro when markers exist
        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        skipIntroChip = pillActionButton(
            label = "Skip Intro ⏭",
            description = "Skip intro",
            bgColor = Color.argb(215, 22, 26, 38),
            fgColor = accentCyan,
            heightDp = 32,
            horizontalPadDp = 14
        ) { skipIntro() }.apply { visibility = View.GONE }

        skipOutroChip = pillActionButton(
            label = "Skip Outro ⏭",
            description = "Skip outro",
            bgColor = Color.argb(215, 22, 26, 38),
            fgColor = accentCyan,
            heightDp = 32,
            horizontalPadDp = 14
        ) { skipOutro() }.apply { visibility = View.GONE }

        chipsRow.addView(skipIntroChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(8) })
        chipsRow.addView(skipOutroChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
        wrapper.addView(chipsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Main Floating Glass Card (matches macmpv PlayerControls)
        controlsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(glassBg, glassBorder, radiusDp = 22f)
            elevation = dpF(12f)
            setPadding(dp(16), dp(12), dp(16), dp(14))
            isClickable = true
        }

        // Row 1: [currentTime] [SeekBar] [duration]
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        currentTimeLabel = TextView(this).apply {
            text = "00:00"
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(textSecondary)
        }
        durationTimeLabel = TextView(this).apply {
            text = "00:00"
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(textSecondary)
        }
        seekBar = SeekBar(this).apply {
            max = 10000
            contentDescription = "Playback position"
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            secondaryProgressTintList = ColorStateList.valueOf(Color.argb(90, 255, 255, 255))
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seek: SeekBar?) {
                scrubbing = true
                wasPlaying = !engine.paused
                engine.pause(true)
                main.removeCallbacks(autoHideControls)
                previewImage.setImageBitmap(null)
                previewImage.visibility = View.GONE
                previewPopover.visibility = View.VISIBLE
                updatePreviewPopoverPosition(seekBar.progress)
            }

            override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || engine.duration <= 0) return
                val target = progress / 10000.0 * engine.duration
                currentTimeLabel.text = playbackTime(target)
                previewLabel.text = playbackTime(target)
                updatePreviewPopoverPosition(progress)
                val source = resolvedSource
                if (source != null) {
                    previews.request(source, target) { bitmap ->
                        if (scrubbing) {
                            if (bitmap != null) {
                                previewImage.setImageBitmap(bitmap)
                                previewImage.visibility = View.VISIBLE
                            } else {
                                previewImage.visibility = View.GONE
                            }
                            updatePreviewPopoverPosition(seekBar.progress)
                        }
                    }
                }
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastPreviewSeek >= 180) {
                    engine.seek(target, exact = false)
                    lastPreviewSeek = now
                }
            }

            override fun onStopTrackingTouch(seek: SeekBar?) {
                val target = seekBar.progress / 10000.0 * engine.duration
                engine.seek(target, exact = true)
                scrubbing = false
                if (wasPlaying) engine.pause(false)
                previewPopover.visibility = View.GONE
                previews.cancel()
                scheduleHide()
            }
        })
        seekRow.addView(currentTimeLabel)
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        seekRow.addView(durationTimeLabel)
        controlsCard.addView(seekRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })

        // Left cluster: Sidebar, Subtitles, Subtitle Appearance, Audio, Markers
        leftCluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sidebarToggleBottomBtn = iconButton("☰", "Toggle playlist", sizeDp = 34, textSizeSp = 13f) {
            setSidebarVisible(!queueVisible)
        }
        val subMenuBtn = iconButton("CC", "Subtitles", sizeDp = 34, textSizeSp = 11.5f) {
            trackDialog("sub")
        }
        val subStyleBtn = iconButton("Aa", "Subtitle appearance & sync", sizeDp = 34, textSizeSp = 12f) {
            showSubtitleSheet(!subtitleSheetVisible)
        }
        val audioMenuBtn = iconButton("♫", "Audio tracks", sizeDp = 34, textSizeSp = 13f) {
            trackDialog("audio")
        }
        val markerMenuBtn = iconButton("⚑", "Intro / outro markers", sizeDp = 34, textSizeSp = 13f) {
            markerDialog()
        }

        leftCluster.addView(sidebarToggleBottomBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(4) })
        leftCluster.addView(subMenuBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(4) })
        leftCluster.addView(subStyleBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(4) })
        leftCluster.addView(audioMenuBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(4) })
        leftCluster.addView(markerMenuBtn, LinearLayout.LayoutParams(dp(34), dp(34)))

        // Center transport cluster: Previous, -10s, White Play/Pause Circle, +10s, Next
        centerCluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        prevButton = iconButton("⏮", "Previous item", sizeDp = 36, textSizeSp = 14f, bgColor = Color.TRANSPARENT) {
            previous()
        }
        val back10Btn = iconButton("−10", "Seek backward 10 seconds", sizeDp = 38, textSizeSp = 11.5f, bgColor = Color.TRANSPARENT) {
            relativeSeek(-10.0)
        }
        playPauseButton = TextView(this).apply {
            text = "▶"
            contentDescription = "Play or pause"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(14, 17, 28))
            gravity = Gravity.CENTER
            background = pillDrawable(Color.WHITE)
            elevation = dpF(4f)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                safely { togglePlayback() }
                scheduleHide()
            }
        }
        val fwd10Btn = iconButton("+10", "Seek forward 10 seconds", sizeDp = 38, textSizeSp = 11.5f, bgColor = Color.TRANSPARENT) {
            relativeSeek(10.0)
        }
        nextButton = iconButton("⏭", "Next item", sizeDp = 36, textSizeSp = 14f, bgColor = Color.TRANSPARENT) {
            queue.next()?.let { play(it) }
        }

        centerCluster.addView(prevButton, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(4) })
        centerCluster.addView(back10Btn, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(8) })
        centerCluster.addView(playPauseButton, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(8) })
        centerCluster.addView(fwd10Btn, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(4) })
        centerCluster.addView(nextButton, LinearLayout.LayoutParams(dp(36), dp(36)))

        // Right cluster: Mute/Volume, Speed Pill, Repeat, Screenshot, Fullscreen
        rightCluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        muteButton = iconButton("🔊", "Volume / mute", sizeDp = 34, textSizeSp = 13f) {
            volumeDialog()
        }
        speedPill = pillActionButton(
            label = "1×",
            description = "Playback speed",
            bgColor = glassPillBg,
            heightDp = 32,
            horizontalPadDp = 10
        ) { speedDialog() }
        repeatButton = iconButton("🔁", "Repeat mode", sizeDp = 34, textSizeSp = 12f) {
            cycleRepeat()
        }
        val screenshotBtn = iconButton("📷", "Save PNG screenshot", sizeDp = 34, textSizeSp = 12.5f) {
            screenshot()
        }
        fullscreenButton = iconButton("⛶", "Toggle fullscreen", sizeDp = 34, textSizeSp = 14f) {
            toggleFullscreen()
        }

        rightCluster.addView(muteButton, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(4) })
        rightCluster.addView(speedPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { marginEnd = dp(4) })
        rightCluster.addView(repeatButton, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(4) })
        rightCluster.addView(screenshotBtn, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(4) })
        rightCluster.addView(fullscreenButton, LinearLayout.LayoutParams(dp(34), dp(34)))

        wideControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        narrowTransportRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val toolsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(16))
            addView(toolsRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        }
        narrowControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(narrowTransportRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(toolsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(6) })
        }
        val screenWidth = resources.displayMetrics.widthPixels
        val horizontalPad = if (isLandscape()) dp(24) else dp(12)
        applyControlsLayout(screenWidth - horizontalPad * 2)

        wrapper.addView(controlsCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return wrapper
    }

    private fun buildQueueSidebar(): LinearLayout {
        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(glassSidebarBg, glassBorder, radiusDp = 22f)
            elevation = dpF(16f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
            isClickable = true
        }

        // Sidebar Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(10))
        }
        val closeBtn = iconButton("✕", "Hide playlist", sizeDp = 30, textSizeSp = 12f) {
            setSidebarVisible(false)
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) })

        val headerTitles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val upNextLabel = TextView(this).apply {
            text = "UP NEXT"
            textSize = 10f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.14f
            setTextColor(textMuted)
        }
        queueSummaryText = TextView(this).apply {
            text = "0 items"
            textSize = 12f
            setTextColor(textSecondary)
        }
        headerTitles.addView(upNextLabel)
        headerTitles.addView(queueSummaryText)
        header.addView(headerTitles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val repeatBtn = pillActionButton("Repeat", "Repeat", heightDp = 28, horizontalPadDp = 10) {
            cycleRepeat()
        }
        header.addView(repeatBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)).apply { marginEnd = dp(6) })

        val addBtn = iconButton("＋", "Add media", sizeDp = 30, textSizeSp = 14f) {
            pickMedia()
        }
        header.addView(addBtn, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(6) })

        val clearBtn = iconButton("⋯", "Queue menu", sizeDp = 30, textSizeSp = 14f) {
            showQueueMenu()
        }
        header.addView(clearBtn, LinearLayout.LayoutParams(dp(30), dp(30)))
        sidebar.addView(header)

        // Scrollable Queue List
        queueRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        queueScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(queueRows)
        }
        sidebar.addView(queueScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Bottom "NOW PLAYING" metadata card (matches macmpv CurrentMediaDetails)
        nowPlayingCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(Color.argb(24, 255, 255, 255), Color.argb(28, 255, 255, 255), radiusDp = 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }
        val npHeader = TextView(this).apply {
            text = "NOW PLAYING"
            textSize = 9.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.12f
            setTextColor(textMuted)
        }
        nowPlayingTitle = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(textPrimary)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, dp(6))
        }
        nowPlayingPills = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nowPlayingStatus = TextView(this).apply {
            textSize = 10.5f
            setTextColor(textSecondary)
            visibility = View.GONE
        }
        nowPlayingCard.addView(npHeader)
        nowPlayingCard.addView(nowPlayingTitle)
        nowPlayingCard.addView(nowPlayingPills)
        nowPlayingCard.addView(nowPlayingStatus)
        sidebar.addView(nowPlayingCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        return sidebar
    }

    private fun buildSubtitleSheet(): FrameLayout {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(80, 0, 0, 0))
            visibility = View.GONE
            setOnClickListener { showSubtitleSheet(false) }
        }
        subtitleSheetCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassDrawable(Color.argb(242, 18, 21, 32), glassBorder, radiusDp = 20f)
            elevation = dpF(18f)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            isClickable = true
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(subtitleSheetCard)
        }
        overlay.addView(
            scroll,
            FrameLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        return overlay
    }

    private fun populateSubtitleSheet() {
        subtitleSheetCard.removeAllViews()
        var currentStyle = store.style

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Subtitles"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(textPrimary)
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val openExtBtn = pillActionButton("Open File…", "Open external subtitles", heightDp = 28, horizontalPadDp = 10) {
            showSubtitleSheet(false)
            pickSubtitle()
        }
        header.addView(openExtBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)).apply { marginEnd = dp(6) })
        val closeBtn = iconButton("✕", "Close subtitle settings", sizeDp = 28, textSizeSp = 12f) {
            showSubtitleSheet(false)
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(dp(28), dp(28)))
        subtitleSheetCard.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })

        fun addLiveSlider(
            label: String,
            minVal: Double,
            maxVal: Double,
            initial: Double,
            formatValue: (Double) -> String,
            onChange: (Double) -> SubtitleStyle
        ) {
            val rowHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val nameView = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(textSecondary)
            }
            val valView = TextView(this).apply {
                text = formatValue(initial)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(textPrimary)
            }
            rowHeader.addView(nameView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            rowHeader.addView(valView)
            subtitleSheetCard.addView(rowHeader)

            val slider = SeekBar(this).apply {
                max = 1000
                progress = (((initial - minVal) / (maxVal - minVal)) * 1000.0).roundToInt().coerceIn(0, 1000)
                progressTintList = ColorStateList.valueOf(accentCyan)
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val raw = minVal + (p / 1000.0) * (maxVal - minVal)
                        val rounded = (raw * 20.0).roundToInt() / 20.0
                        valView.text = formatValue(rounded)
                        currentStyle = onChange(rounded)
                        store.style = currentStyle
                        engine.applySubtitles(currentStyle)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) = Unit
                    override fun onStopTrackingTouch(s: SeekBar?) = Unit
                })
            }
            subtitleSheetCard.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
        }

        addLiveSlider(
            label = "Size",
            minVal = 0.5,
            maxVal = 2.5,
            initial = currentStyle.scale,
            formatValue = { "${(it * 100).roundToInt()}%" },
            onChange = { currentStyle.copy(scale = it) }
        )

        addLiveSlider(
            label = "Outline",
            minVal = 0.0,
            maxVal = 8.0,
            initial = currentStyle.outline,
            formatValue = { String.format(Locale.US, "%.1f", it) },
            onChange = { currentStyle.copy(outline = it) }
        )

        addLiveSlider(
            label = "Background",
            minVal = 0.0,
            maxVal = 1.0,
            initial = currentStyle.background,
            formatValue = { "${(it * 100).roundToInt()}%" },
            onChange = { currentStyle.copy(background = it) }
        )

        addLiveSlider(
            label = "Sync Delay",
            minVal = -10.0,
            maxVal = 10.0,
            initial = currentStyle.delay.coerceIn(-10.0, 10.0),
            formatValue = { String.format(Locale.US, "%+.1fs", it) },
            onChange = { currentStyle.copy(delay = it) }
        )

        // Bold + Color Swatches Row
        val styleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(10))
        }
        val boldCheck = CheckBox(this).apply {
            text = "Bold"
            setTextColor(textPrimary)
            isChecked = currentStyle.bold
            buttonTintList = ColorStateList.valueOf(accentCyan)
            setOnCheckedChangeListener { _, checked ->
                currentStyle = currentStyle.copy(bold = checked)
                store.style = currentStyle
                engine.applySubtitles(currentStyle)
            }
        }
        styleRow.addView(boldCheck, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val colors = listOf(
            "#FFFFFF" to Color.WHITE,
            "#FFEB3B" to Color.rgb(255, 235, 59),
            "#66E2CB" to accentCyan,
            "#FF9800" to Color.rgb(255, 152, 0)
        )
        colors.forEach { (hex, colorInt) ->
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorInt)
                    setStroke(dp(2), if (currentStyle.color.equals(hex, ignoreCase = true)) accentCyan else Color.argb(90, 255, 255, 255))
                }
                contentDescription = "Subtitle color $hex"
                setOnClickListener {
                    currentStyle = currentStyle.copy(color = hex)
                    store.style = currentStyle
                    engine.applySubtitles(currentStyle)
                    populateSubtitleSheet()
                }
            }
            styleRow.addView(swatch, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(8) })
        }
        subtitleSheetCard.addView(styleRow)

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val resetBtn = pillActionButton("Reset All", "Reset subtitle settings", heightDp = 30, horizontalPadDp = 12) {
            store.style = SubtitleStyle()
            engine.applySubtitles(store.style)
            populateSubtitleSheet()
        }
        footer.addView(resetBtn)
        subtitleSheetCard.addView(footer)
    }

    private fun showSubtitleSheet(show: Boolean) {
        subtitleSheetVisible = show
        if (show) {
            populateSubtitleSheet()
            subtitleSheetOverlay.visibility = View.VISIBLE
            applyResponsiveLayout()
            // Sheet is modal: keep it above the queue drawer, controls stay
            // on top so transport remains reachable while tuning subtitles.
            raiseTransientOverlays()
            main.removeCallbacks(autoHideControls)
        } else {
            subtitleSheetOverlay.visibility = View.GONE
            scheduleHide()
        }
    }

    private fun buildErrorToast(): LinearLayout {
        val toast = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassDrawable(Color.argb(235, 34, 22, 18), Color.argb(140, 255, 152, 0), radiusDp = 999f)
            elevation = dpF(10f)
            setPadding(dp(16), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        val warnIcon = TextView(this).apply {
            text = "⚠"
            textSize = 14f
            setTextColor(Color.rgb(255, 167, 38))
        }
        errorToastText = TextView(this).apply {
            textSize = 12f
            setTextColor(textPrimary)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
        }
        val dismissBtn = iconButton("✕", "Dismiss error", sizeDp = 26, textSizeSp = 11f) {
            dismissError()
        }
        toast.addView(warnIcon)
        toast.addView(errorToastText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        toast.addView(dismissBtn, LinearLayout.LayoutParams(dp(26), dp(26)))
        return toast
    }

    private fun attachSurfaceGestures() {
        var swipeStartY = 0f
        var swipeStartX = 0f
        var swipingVolume = false
        var initialSwipeVolume = 80.0

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                swipeStartX = e.x
                swipeStartY = e.y
                swipingVolume = false
                initialSwipeVolume = volume
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (queueVisible) {
                    setSidebarVisible(false)
                    return true
                }
                if (engine.hasFile || queue.current != null) {
                    val nextVis = !controlsVisible
                    setControlsOverlayVisible(nextVis, showHeaderInFullscreen = nextVis)
                } else {
                    setControlsOverlayVisible(true, showHeaderInFullscreen = true)
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = surface.width.takeIf { it > 0 } ?: root.width
                if (engine.hasFile && w > 0) {
                    when {
                        e.x < w * 0.32f -> {
                            relativeSeek(-10.0)
                            flashGestureHud("⏪  −10s")
                            return true
                        }
                        e.x > w * 0.68f -> {
                            relativeSeek(10.0)
                            flashGestureHud("⏩  +10s")
                            return true
                        }
                    }
                }
                toggleFullscreen()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!engine.hasFile || scrubbing) return false
                val startX = e1?.x ?: swipeStartX
                val startY = e1?.y ?: swipeStartY
                val dy = startY - e2.y
                val dx = e2.x - startX
                val w = surface.width.takeIf { it > 0 } ?: return false
                val h = surface.height.takeIf { it > 0 } ?: return false
                if (!swipingVolume && abs(dy) > dp(24) && abs(dy) > abs(dx) * 1.5f && startX > w * 0.5f) {
                    swipingVolume = true
                }
                if (swipingVolume) {
                    val delta = (dy / (h * 0.65f)) * 100.0
                    val newVol = (initialSwipeVolume + delta).coerceIn(0.0, 100.0)
                    volume = newVol
                    muted = newVol == 0.0
                    store.volume = volume
                    engine.set("volume", volume)
                    flashGestureHud(if (muted) "🔇 Muted" else "🔊 Volume ${volume.roundToInt()}%")
                    renderPlayback()
                    return true
                }
                return false
            }
        })

        surface.setOnTouchListener { view, event ->
            val consumed = detector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) view.performClick()
            consumed
        }
    }

    private fun flashGestureHud(text: String) {
        gestureHud.text = text
        gestureHud.alpha = 1f
        gestureHud.visibility = View.VISIBLE
        main.removeCallbacks(hideGestureHud)
        main.postDelayed(hideGestureHud, 950)
    }

    private fun updatePreviewPopoverPosition(progress: Int) {
        root.post {
            if (destroyed || previewPopover.visibility != View.VISIBLE) return@post
            val seekCoords = IntArray(2)
            val rootCoords = IntArray(2)
            seekBar.getLocationInWindow(seekCoords)
            root.getLocationInWindow(rootCoords)
            val relX = seekCoords[0] - rootCoords[0]
            val trackStart = relX + seekBar.paddingLeft
            val trackWidth = (seekBar.width - seekBar.paddingLeft - seekBar.paddingRight).coerceAtLeast(1)
            val fraction = (progress / 10000f).coerceIn(0f, 1f)
            val thumbCenterX = trackStart + (trackWidth * fraction).roundToInt()

            val popWidth = previewPopover.width.takeIf { it > 0 } ?: dp(174)
            val minX = insetLeft + dp(12)
            val maxX = (root.width - insetRight - popWidth - dp(12)).coerceAtLeast(minX)
            val targetX = (thumbCenterX - popWidth / 2).coerceIn(minX, maxX)

            val controlsHeight = controlsContainer.height.takeIf { it > 0 } ?: dp(110)
            val bottomOffset = controlsHeight + insetBottom + dp(12)

            val lp = previewPopover.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = targetX
            lp.bottomMargin = bottomOffset
            previewPopover.layoutParams = lp
        }
    }

    private fun applyResponsiveLayout() {
        if (!::root.isInitialized) return
        val landscape = isLandscape()
        val horizontalPad = if (landscape) dp(24) else dp(12)
        val bottomPad = if (landscape) dp(16) else dp(12)

        // Header safe margins
        (headerBar.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = insetLeft + (if (landscape) dp(12) else dp(4))
            topMargin = insetTop + dp(6)
            rightMargin = insetRight + (if (landscape) dp(12) else dp(4))
            headerBar.layoutParams = this
        }

        // Error toast safe margins
        (errorToast.layoutParams as FrameLayout.LayoutParams).apply {
            topMargin = insetTop + dp(56)
            leftMargin = insetLeft + dp(16)
            rightMargin = insetRight + dp(16)
            errorToast.layoutParams = this
        }

        // Compact the glass control card on short landscape screens so the
        // transport row never eats the video height.
        controlsCard.setPadding(dp(16), if (landscape) dp(8) else dp(12), dp(16), if (landscape) dp(10) else dp(14))

        // Controls container safe margins & max width cap for wide landscape screens
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val maxControlsWidth = dp(860)
        val availWidth = screenWidth - insetLeft - insetRight - horizontalPad * 2
        val sideExtra = if (availWidth > maxControlsWidth) (availWidth - maxControlsWidth) / 2 else 0
        (controlsContainer.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = insetLeft + horizontalPad + sideExtra
            rightMargin = insetRight + horizontalPad + sideExtra
            bottomMargin = insetBottom + bottomPad
            controlsContainer.layoutParams = this
        }
        applyControlsLayout(availWidth)

        // Queue sidebar floats above the video and always clears the control
        // bar while it is visible — in landscape too, where the old code
        // dropped the bottom margin and let the drawer slide under transport.
        val sidebarWidth = if (landscape) dp(320) else minOf(dp(300), (screenWidth - dp(32)).coerceAtLeast(dp(240)))
        val controlsH = controlsContainer.height.takeIf { it > 0 }
            ?: if (controlsUseNarrowLayout == true) dp(168) else if (landscape) dp(96) else dp(126)
        (queueSidebar.layoutParams as FrameLayout.LayoutParams).apply {
            width = sidebarWidth
            leftMargin = insetLeft + dp(12)
            topMargin = insetTop + dp(54)
            bottomMargin = insetBottom + if (controlsVisible) controlsH + dp(12) else dp(16)
            queueSidebar.layoutParams = this
        }

        // Seek preview must fit short landscape screens: shrink the thumbnail
        // so popover + controls never overflow the video height.
        (previewImage.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (landscape) dp(144) else dp(168)
            height = if (landscape) dp(70) else dp(94)
            previewImage.layoutParams = this
        }

        // Subtitle sheet: fixed 340dp card, but cap the scroll height so it
        // stays fully on-screen in landscape instead of overflowing top/bottom.
        if (subtitleSheetOverlay.childCount > 0) {
            val scroll = subtitleSheetOverlay.getChildAt(0)
            (scroll.layoutParams as FrameLayout.LayoutParams).apply {
                width = minOf(dp(340), (screenWidth - insetLeft - insetRight - dp(32)).coerceAtLeast(dp(240)))
                height = if (landscape) {
                    (screenHeight - insetTop - insetBottom - dp(32)).coerceAtLeast(dp(200))
                } else {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                }
                scroll.layoutParams = this
            }
        }

        // Welcome card safe padding
        welcomeCard.setPadding(
            dp(24) + insetLeft / 2,
            dp(16) + insetTop / 2,
            dp(24) + insetRight / 2,
            dp(32) + insetBottom / 2
        )

        root.post { clearance() }
    }

    /** One row when the glass bar fits; transport plus a scrolling tool row when it does not. */
    private fun applyControlsLayout(availableWidth: Int) {
        if (!::leftCluster.isInitialized || !::controlsCard.isInitialized) return
        val inner = (availableWidth - dp(32)).coerceAtLeast(0)
        val narrow = inner < dp(640)
        if (controlsUseNarrowLayout == narrow && leftCluster.parent != null) return
        controlsUseNarrowLayout = narrow
        wideControlsRow.removeAllViews()
        narrowTransportRow.removeAllViews()
        toolsRow.removeAllViews()
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        if (narrow) {
            (wideControlsRow.parent as? ViewGroup)?.removeView(wideControlsRow)
            toolsRow.addView(leftCluster, LinearLayout.LayoutParams(wrap, wrap))
            toolsRow.addView(rightCluster, LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = dp(8) })
            narrowTransportRow.addView(centerCluster, LinearLayout.LayoutParams(wrap, wrap))
            if (narrowControls.parent == null) {
                controlsCard.addView(narrowControls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap))
            }
        } else {
            (narrowControls.parent as? ViewGroup)?.removeView(narrowControls)
            wideControlsRow.addView(leftCluster, LinearLayout.LayoutParams(wrap, wrap))
            wideControlsRow.addView(centerCluster, LinearLayout.LayoutParams(0, wrap, 1f))
            wideControlsRow.addView(rightCluster, LinearLayout.LayoutParams(wrap, wrap))
            if (wideControlsRow.parent == null) {
                controlsCard.addView(wideControlsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wrap))
            }
        }
    }

    private fun updateSidebarBottomMargin() {
        if (!::queueSidebar.isInitialized) return
        val landscape = isLandscape()
        val controlsH = controlsContainer.height.takeIf { it > 0 }
            ?: if (controlsUseNarrowLayout == true) dp(168) else if (landscape) dp(96) else dp(126)
        val lp = queueSidebar.layoutParams as? FrameLayout.LayoutParams ?: return
        val desired = insetBottom + if (controlsVisible) controlsH + dp(12) else dp(16)
        if (lp.bottomMargin != desired) {
            lp.bottomMargin = desired
            queueSidebar.layoutParams = lp
        }
    }

    private fun syncOrientationMode(config: Configuration) {
        // Rotation never forces fullscreen: entering landscape used to hide the
        // title, torrent status and queue toggle (header GONE) right where the
        // app is used most. Fullscreen stays purely user-driven (button,
        // double-tap, F key), so rotation only re-applies safe-area layout.
        applyResponsiveLayout()
    }

    private fun setSidebarVisible(visible: Boolean) {
        queueVisible = visible
        store.sidebarVisible = visible
        renderQueue()
        applyResponsiveLayout()
        raiseTransientOverlays()
        if (!visible) scheduleHide() else main.removeCallbacks(autoHideControls)
    }

    /** Keep transport/header/preview/HUD interactive above the queue scrim and
     * subtitle sheet: those layers are added after the controls at build time,
     * so without this the sidebar would swallow taps on the transport cluster. */
    private fun raiseTransientOverlays() {
        if (!::root.isInitialized) return
        root.bringChildToFront(queueSidebar)
        root.bringChildToFront(subtitleSheetOverlay)
        root.bringChildToFront(previewPopover)
        root.bringChildToFront(headerBar)
        root.bringChildToFront(controlsContainer)
        root.bringChildToFront(gestureHud)
        root.bringChildToFront(errorToast)
        root.bringChildToFront(loadingBadge)
    }

    private fun setControlsOverlayVisible(visible: Boolean, showHeaderInFullscreen: Boolean = false) {
        controlsVisible = visible
        val vis = if (visible) View.VISIBLE else View.GONE
        controlsContainer.visibility = vis
        topGradient.visibility = vis
        bottomGradient.visibility = vis
        // Header (title, torrent status, queue toggle) always follows the
        // controls overlay — including landscape fullscreen, matching macmpv's
        // fullscreen controls which keep the title reachable.
        headerBar.visibility = if (visible || showHeaderInFullscreen) View.VISIBLE else View.GONE
        applyResponsiveLayout()
        clearance()
        if (visible) scheduleHide() else main.removeCallbacks(autoHideControls)
    }

    internal fun renderPlayback() {
        if (!::titleText.isInitialized) return
        val item = queue.current
        val hasMedia = item != null

        titleText.text = item?.title ?: "andrompv"
        welcomeCard.visibility = if (hasMedia) View.GONE else View.VISIBLE
        loadingBadge.visibility = if (hasMedia && engine.loading) View.VISIBLE else View.GONE

        // Update header metadata pills (Resolution • Codec • FPS or Torrent status)
        subtitleMetaRow.removeAllViews()
        if (item != null) {
            val meta = item.metadata ?: engine.currentMediaMetadata()
            val subtitleText = when {
                item.isTorrent -> torrents.statusLabel()
                meta != null && meta.badges.isNotEmpty() -> meta.badges.joinToString("  •  ")
                engine.loading -> "Loading media…"
                else -> item.fileExtension.ifEmpty { "STREAM" }
            }
            val metaLabel = TextView(this).apply {
                text = subtitleText
                textSize = 11f
                setTextColor(textSecondary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            subtitleMetaRow.addView(metaLabel)
        } else {
            val readyLabel = TextView(this).apply {
                text = if (MPVEngine.preferHardwareDecoding()) "Ready · Hardware-accelerated libmpv" else "Ready · libmpv software decoding"
                textSize = 11f
                setTextColor(textMuted)
            }
            subtitleMetaRow.addView(readyLabel)
        }

        // Transport state
        val isPaused = !hasMedia || engine.paused
        playPauseButton.text = if (isPaused) "▶" else "⏸"
        prevButton.alpha = if (queue.canGoPrevious(engine.position)) 1f else 0.35f
        nextButton.alpha = if (queue.canGoNext) 1f else 0.35f

        if (!scrubbing) {
            currentTimeLabel.text = playbackTime(engine.position)
            durationTimeLabel.text = playbackTime(engine.duration)
            seekBar.progress = if (engine.duration > 0) (engine.position / engine.duration * 10000).toInt().coerceIn(0, 10000) else 0
        }
        seekBar.isEnabled = engine.duration > 0

        // Speed, Mute, Repeat, Fullscreen indicators
        speedPill.text = if (speed == speed.roundToInt().toDouble()) "${speed.roundToInt()}×" else "${speed}×"
        muteButton.text = if (muted || volume == 0.0) "🔇" else "🔊"
        repeatButton.text = when (queue.repeat) {
            RepeatMode.OFF -> "🔁"
            RepeatMode.ALL -> "🔁"
            RepeatMode.ONE -> "🔂"
        }
        repeatButton.setTextColor(if (queue.repeat == RepeatMode.OFF) textSecondary else accentCyan)
        repeatButton.contentDescription = "Repeat ${queue.repeat.name.lowercase()}"
        fullscreenButton.text = if (fullscreen) "⤢" else "⛶"
        sidebarToggleBtn.setTextColor(if (queueVisible) accentCyan else textPrimary)
        sidebarToggleBottomBtn.setTextColor(if (queueVisible) accentCyan else textPrimary)

        // Intro / Outro skip chips (macmpv shows these when markers are set)
        if (item != null) {
            val m = store.markers(item)
            skipIntroChip.visibility = if (m.intro != null) View.VISIBLE else View.GONE
            skipOutroChip.visibility = if (m.outro != null) View.VISIBLE else View.GONE
        } else {
            skipIntroChip.visibility = View.GONE
            skipOutroChip.visibility = View.GONE
        }

        // Keep screen on during active foreground playback
        if (hasMedia && !engine.paused && foreground) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Save resume progress every ~1s
        if (item != null && engine.hasFile && !scrubbing && abs(engine.position - lastSaved) >= 1.0) {
            store.remember(item, engine.position, engine.duration)
            lastSaved = engine.position
        }
    }

    internal fun renderQueue() {
        if (!::queueSidebar.isInitialized) return
        queueSidebar.visibility = if (queueVisible) View.VISIBLE else View.GONE
        sidebarScrim.visibility = if (queueVisible) View.VISIBLE else View.GONE

        val groups = queue.groups
        val itemCount = queue.items.size
        val collectionCount = groups.count { it.isCollapsible }
        queueSummaryText.text = buildString {
            append("$itemCount ${if (itemCount == 1) "item" else "items"}")
            if (collectionCount > 0) {
                append(" • $collectionCount ${if (collectionCount == 1) "collection" else "collections"}")
            }
            if (queue.repeat != RepeatMode.OFF) {
                append(" • ${queue.repeat.label}")
            }
        }

        queueRows.removeAllViews()
        if (queue.items.isEmpty()) {
            val emptyBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(36), dp(16), dp(36))
            }
            val emptyTitle = TextView(this).apply {
                text = "Your queue is empty"
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(textPrimary)
                gravity = Gravity.CENTER
            }
            val emptySub = TextView(this).apply {
                text = "Open media files, playlists, or a stream to play them in order."
                textSize = 12f
                setTextColor(textSecondary)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(14))
            }
            val addMediaBtn = pillActionButton("Add Media", "Add media to queue") { pickMedia() }
            emptyBox.addView(emptyTitle)
            emptyBox.addView(emptySub)
            emptyBox.addView(addMediaBtn)
            queueRows.addView(emptyBox)
        } else {
            for (group in groups) {
                if (group.isCollapsible) {
                    val isCollapsed = group.id in collapsedGroups
                    val containsCurrent = group.items.any { it.id == queue.currentId }
                    val groupCard = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        background = glassDrawable(
                            fillColor = if (containsCurrent) Color.argb(26, 255, 255, 255) else Color.argb(12, 255, 255, 255),
                            strokeColor = if (containsCurrent) Color.argb(45, 255, 255, 255) else Color.argb(22, 255, 255, 255),
                            radiusDp = 13f
                        )
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                    }
                    val groupHeader = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(6), dp(4), dp(6))
                        isClickable = true
                        setOnClickListener {
                            if (!collapsedGroups.add(group.id)) collapsedGroups.remove(group.id)
                            renderQueue()
                        }
                    }
                    val chevron = TextView(this).apply {
                        text = if (isCollapsed) "▸" else "▾"
                        textSize = 13f
                        setTextColor(textSecondary)
                        setPadding(0, 0, dp(8), 0)
                    }
                    val groupTitles = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    val gTitle = TextView(this).apply {
                        text = group.title
                        textSize = 12.5f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(textPrimary)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    val gSub = TextView(this).apply {
                        text = "${group.items.size} ${if (group.items.size == 1) "item" else "items"}"
                        textSize = 10.5f
                        setTextColor(textMuted)
                    }
                    groupTitles.addView(gTitle)
                    groupTitles.addView(gSub)
                    val groupMore = iconButton("⋮", "Collection options", sizeDp = 30, textSizeSp = 14f, bgColor = Color.TRANSPARENT) {
                        groupOptions(group.id)
                    }
                    groupHeader.addView(chevron)
                    groupHeader.addView(groupTitles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    groupHeader.addView(groupMore, LinearLayout.LayoutParams(dp(30), dp(30)))
                    groupCard.addView(groupHeader)

                    if (!isCollapsed) {
                        group.items.forEach { item ->
                            groupCard.addView(buildQueueItemRow(item, indented = true))
                        }
                    }
                    queueRows.addView(groupCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(6)
                    })
                } else {
                    group.items.firstOrNull()?.let { item ->
                        queueRows.addView(buildQueueItemRow(item, indented = false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(4)
                        })
                    }
                }
            }
        }

        // Update NOW PLAYING bottom card
        val current = queue.current
        if (current != null) {
            nowPlayingCard.visibility = View.VISIBLE
            nowPlayingTitle.text = current.title
            nowPlayingPills.removeAllViews()
            val meta = current.metadata ?: engine.currentMediaMetadata()
            val badges = meta?.badges.orEmpty()
            if (badges.isNotEmpty()) {
                nowPlayingPills.visibility = View.VISIBLE
                nowPlayingStatus.visibility = View.GONE
                badges.forEach { badge ->
                    val pill = TextView(this).apply {
                        text = badge
                        textSize = 9.5f
                        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                        setTextColor(textSecondary)
                        background = glassDrawable(Color.argb(26, 255, 255, 255), Color.TRANSPARENT, radiusDp = 999f)
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                    }
                    nowPlayingPills.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(6)
                    })
                }
            } else {
                nowPlayingPills.visibility = View.GONE
                nowPlayingStatus.visibility = View.VISIBLE
                nowPlayingStatus.text = when {
                    current.isTorrent && current.torrentIndex == null -> "Reading torrent contents…"
                    current.isTorrent -> "Streaming from BitTorrent"
                    current.probeFailed -> "Media info unavailable"
                    else -> "Reading media info…"
                }
            }
        } else {
            nowPlayingCard.visibility = View.GONE
        }
    }

    private fun buildQueueItemRow(item: QueueItem, indented: Boolean): View {
        val isCurrent = item.id == queue.currentId
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillDrawable(
                fillColor = if (isCurrent) Color.argb(38, 102, 226, 203) else Color.TRANSPARENT,
                strokeColor = if (isCurrent) Color.argb(65, 102, 226, 203) else Color.TRANSPARENT,
                radiusDp = 11f
            )
            setPadding(dp(if (indented) 12 else 8), dp(7), dp(6), dp(7))
            isClickable = true
            isFocusable = true
            setOnClickListener { safely { play(item) } }
            setOnLongClickListener {
                val clip = ClipData.newPlainText("queue_item_id", item.id)
                startDragAndDrop(clip, View.DragShadowBuilder(this), item.id, 0)
                true
            }
            setOnDragListener { v, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    DragEvent.ACTION_DRAG_ENTERED -> { v.alpha = 0.65f; true }
                    DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { v.alpha = 1f; true }
                    DragEvent.ACTION_DROP -> {
                        v.alpha = 1f
                        val draggedId = event.localState as? String
                            ?: event.clipData?.getItemAt(0)?.text?.toString()
                        if (draggedId != null && draggedId != item.id) {
                            queue.moveBefore(draggedId, item.id)
                            store.save(queue)
                            renderQueue()
                            renderPlayback()
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        // Badge icon (like macmpv QueueRow)
        val badge = TextView(this).apply {
            text = if (isCurrent) "♫" else "▶"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isCurrent) Color.WHITE else textSecondary)
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (isCurrent) intArrayOf(accentIndigo, accentCyan)
                else intArrayOf(Color.argb(32, 255, 255, 255), Color.argb(14, 255, 255, 255))
            ).apply { cornerRadius = dpF(8f) }
        }
        rowView.addView(badge, LinearLayout.LayoutParams(dp(36), dp(30)).apply { marginEnd = dp(10) })

        // Title + extension/duration/size subline
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleView = TextView(this).apply {
            text = item.title
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (isCurrent) textPrimary else Color.argb(225, 255, 255, 255))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val subParts = mutableListOf<String>()
        if (item.isTorrent && item.torrentIndex == null) {
            subParts.add("READING FILES…")
        } else {
            subParts.add(item.fileExtension.ifEmpty { "STREAM" })
        }
        item.sizeLabel?.let { subParts.add(it) }
            ?: item.metadata?.duration?.takeIf { it > 0 }?.let { subParts.add(playbackTime(it)) }

        val subView = TextView(this).apply {
            text = subParts.joinToString(" • ")
            textSize = 9.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(textMuted)
            maxLines = 1
        }
        textCol.addView(titleView)
        textCol.addView(subView)
        rowView.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val moreBtn = iconButton("⋮", "Options for ${item.title}", sizeDp = 30, textSizeSp = 14f, bgColor = Color.TRANSPARENT, fgColor = textSecondary) {
            itemOptions(item)
        }
        rowView.addView(moreBtn, LinearLayout.LayoutParams(dp(30), dp(30)))

        return rowView
    }

    internal fun play(item: QueueItem, resume: Boolean = true) {
        if (!foreground || !surfaceReady) {
            pendingPlay = item
            queue.currentId = item.id
            renderPlayback()
            renderQueue()
            return
        }
        torrents.clearBackgroundSuspend()
        remember()
        requestGeneration++
        val token = requestGeneration
        previews.cancel()
        resolvedSource = null
        lastSaved = -1.0
        dismissError()
        queue.currentId = item.id
        pendingPlay = null
        if (item.isTorrent) {
            engine.stop()
            renderPlayback()
            torrents.open(item) { result ->
                if (destroyed || token != requestGeneration || queue.currentId != item.id) return@open
                result.fold(
                    onSuccess = { opened ->
                        if (item.torrentIndex == null) {
                            val index = queue.items.indexOfFirst { it.id == item.id }
                            if (index < 0) return@fold
                            queue.items.removeAt(index)
                            queue.items.addAll(index, opened.items)
                            store.save(queue)
                            opened.items.firstOrNull()?.let { play(it, resume) }
                        } else {
                            loadSource(item, opened.url ?: error("No torrent stream"), resume)
                        }
                        renderQueue()
                        renderPlayback()
                    },
                    onFailure = { showError(it.message ?: "Torrent playback failed") }
                )
            }
        } else {
            torrents.pause()
            loadSource(item, item.uri, resume)
        }
        renderPlayback()
        renderQueue()
        scheduleHide()
    }

    private fun loadSource(item: QueueItem, source: String, resume: Boolean) {
        safely {
            resolvedSource = source
            engine.load(source, if (resume) store.resume(item) else 0.0)
        }
    }

    private fun remember() {
        queue.current?.takeIf { engine.hasFile }?.let {
            store.remember(it, engine.position, engine.duration)
        }
    }

    private fun togglePlayback() {
        val current = queue.current
        if (current?.isTorrent == true && engine.paused && (torrents.isBackgroundSuspended() || !engine.hasFile)) {
            play(current)
            return
        }
        if (current == null) {
            if (queue.items.isNotEmpty()) play(queue.items.first()) else pickMedia()
            return
        }
        if (!engine.hasFile && !engine.loading) {
            queue.current?.let { play(it) }
        } else {
            engine.pause(!engine.paused)
        }
        renderPlayback()
        scheduleHide()
    }

    private fun previous() {
        if (engine.position > 3) engine.seek(0.0)
        else queue.previous(engine.position)?.let { play(it) }
    }

    private fun relativeSeek(seconds: Double) {
        if (!engine.hasFile && queue.current == null) return
        val maxDur = engine.duration.coerceAtLeast(0.0)
        val target = if (maxDur > 0) (engine.position + seconds).coerceIn(0.0, maxDur) else (engine.position + seconds).coerceAtLeast(0.0)
        engine.seek(target)
        renderPlayback()
    }

    private fun skipIntro() {
        val item = queue.current ?: return
        val intro = store.markers(item).intro ?: return
        engine.seek(intro)
        renderPlayback()
    }

    private fun skipOutro() {
        val item = queue.current ?: return
        store.markers(item).outro ?: return
        val next = queue.next()
        if (next != null) {
            play(next)
        } else {
            engine.seek(engine.duration)
            engine.pause(true)
        }
        renderPlayback()
    }

    private fun cycleRepeat() {
        queue.repeat = queue.repeat.next()
        store.save(queue)
        renderQueue()
        renderPlayback()
        flashGestureHud(queue.repeat.label)
    }

    private fun pickMedia() = startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = "*/*"
        addCategory(Intent.CATEGORY_OPENABLE)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }, OPEN_MEDIA)

    private fun pickSubtitle() {
        if (queue.current == null) {
            toast("Open a media file first")
            return
        }
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, OPEN_SUBTITLE)
    }

    private fun openUrlDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val subtitle = TextView(this).apply {
            text = "HTTP, HTTPS, RTMP, RTSP, HLS, and magnet links are supported."
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, 0, 0, dp(8))
        }
        val input = EditText(this).apply {
            hint = "https://example.com/video.m3u8 or magnet:?xt=..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        container.addView(subtitle)
        container.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Open Network Stream")
            .setView(container)
            .setPositiveButton("Open", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = input.text.toString().trim()
                if (!MediaSupport.isNetwork(url)) {
                    input.error = "Enter a valid HTTP, HTTPS, RTMP, RTSP or magnet URL"
                } else {
                    enqueueUris(listOf(Uri.parse(url)))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun name(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) return it.getString(0) ?: "Media"
                }
            }
        }
        if (uri.scheme == "magnet") return uri.getQueryParameter("dn") ?: "Magnet stream"
        return uri.lastPathSegment?.takeIf(String::isNotBlank) ?: uri.host ?: "Media"
    }

    private fun retain(uri: Uri) {
        if (uri.scheme == "content") {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                /* Shared URIs can grant only temporary access. */
            }
        }
    }

    internal fun enqueueUris(uris: List<Uri>) {
        val group = UUID.randomUUID().toString()
        io.execute {
            val result = runCatching {
                uris.flatMap { uri ->
                    retain(uri)
                    val title = name(uri)
                    if (MediaSupport.extension(title) in setOf("m3u", "m3u8") && uri.scheme in setOf("content", "file")) {
                        val text = contentResolver.openInputStream(uri)?.use {
                            String(it.readBytesLimited(2 * 1024 * 1024), Charsets.UTF_8)
                        } ?: error("Cannot read playlist")
                        if (MediaSupport.isHls(text)) {
                            listOf(QueueItem(uri.toString(), title, group = group, groupTitle = title, groupKind = GroupKind.STANDALONE))
                        } else {
                            val entries = MediaSupport.playlistEntries(text) { line ->
                                if (MediaSupport.isNetwork(line)) line
                                else if (uri.scheme == "file") Uri.fromFile(File(File(uri.path!!).parentFile, line)).toString()
                                else null
                            }
                            require(entries.isNotEmpty()) {
                                "This playlist has no accessible URLs. Android document playlists need absolute network URLs; pick local media files together instead."
                            }
                            entries.map {
                                QueueItem(it, name(Uri.parse(it)), group = group, groupTitle = title, groupKind = GroupKind.PLAYLIST)
                            }
                        }
                    } else {
                        val kind = when {
                            MediaSupport.isTorrent(uri.toString(), title) -> GroupKind.TORRENT
                            uris.size > 1 -> GroupKind.PLAYLIST
                            else -> GroupKind.STANDALONE
                        }
                        listOf(
                            QueueItem(
                                uri = uri.toString(),
                                title = title,
                                group = group,
                                groupTitle = if (uris.size > 1) "Playlist" else title,
                                groupKind = kind
                            )
                        )
                    }
                }
            }
            main.post {
                if (destroyed) return@post
                result.fold(
                    onSuccess = { items ->
                        val firstAdded = queue.enqueue(items)
                        if (queue.current == null && firstAdded != null) {
                            play(firstAdded)
                        } else if (firstAdded != null && !engine.hasFile && !engine.loading) {
                            play(firstAdded)
                        }
                        store.save(queue)
                        renderQueue()
                        renderPlayback()
                        if (items.size > 1) probeUnprobedQueueItems()
                    },
                    onFailure = { showError(it.message ?: "Could not open media") }
                )
            }
        }
    }

    private fun probeUnprobedQueueItems() {
        val candidates = queue.items.filter { it.metadata == null && !it.probeFailed && !it.isTorrent }
        if (candidates.isEmpty()) return
        io.execute {
            for (item in candidates) {
                if (destroyed) return@execute
                val uri = runCatching { Uri.parse(item.uri) }.getOrNull()
                if (uri == null || uri.scheme !in setOf("file", "content")) continue
                val meta = runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        if (uri.scheme == "content") retriever.setDataSource(this, uri)
                        else retriever.setDataSource(uri.path ?: item.uri)
                        val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                        val fps = if (android.os.Build.VERSION.SDK_INT >= 23) {
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
                        } else null
                        val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                        val fileLen = if (uri.scheme == "file") uri.path?.let(::File)?.length() else null
                        MediaMetadata(
                            duration = durMs?.let { it / 1000.0 },
                            width = w,
                            height = h,
                            frameRate = fps,
                            videoCodec = mime?.substringAfter('/')?.uppercase(),
                            fileSize = fileLen
                        )
                    } finally {
                        runCatching { retriever.release() }
                    }
                }.getOrNull()
                main.post {
                    if (destroyed) return@post
                    queue.updateMetadata(item.id, meta, probeFailed = meta == null)
                    store.save(queue)
                    if (queueVisible) renderQueue()
                    if (queue.currentId == item.id) renderPlayback()
                }
            }
        }
    }

    private fun showQueueMenu() {
        val options = arrayOf(
            "Add Media Files…",
            "Open Network Stream…",
            "Repeat Mode (${queue.repeat.label})",
            "Torrent Downloads",
            "Clear Queue"
        )
        AlertDialog.Builder(this)
            .setTitle("Queue")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickMedia()
                    1 -> openUrlDialog()
                    2 -> cycleRepeat()
                    3 -> downloadDialog()
                    4 -> confirmClearQueue()
                }
            }
            .show()
    }

    private fun confirmClearQueue() {
        AlertDialog.Builder(this)
            .setTitle("Clear queue?")
            .setMessage("Removes queue entries only. Your files and torrent downloads are kept.")
            .setPositiveButton("Clear") { _, _ ->
                remember()
                requestGeneration++
                pendingPlay = null
                queue.items.clear()
                queue.currentId = null
                engine.stop()
                torrents.pause()
                resolvedSource = null
                store.save(queue)
                renderQueue()
                renderPlayback()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showOptions() {
        main.removeCallbacks(autoHideControls)
        val options = arrayOf(
            "Playback speed (${speed}×)",
            "Volume / mute (${if (muted) "Muted" else "${volume.roundToInt()}%"})",
            "Repeat: ${queue.repeat.name.lowercase()}",
            "Audio track",
            "Subtitle track",
            "Open external subtitles…",
            "Subtitle appearance & sync",
            "Intro / outro markers",
            "Save PNG screenshot",
            "Media information",
            if (fullscreen) "Exit fullscreen" else "Fullscreen",
            "Torrent downloads"
        )
        AlertDialog.Builder(this)
            .setTitle("Player options")
            .setItems(options) { _, which ->
                safely {
                    when (which) {
                        0 -> speedDialog()
                        1 -> volumeDialog()
                        2 -> {
                            queue.repeat = queue.repeat.next()
                            store.save(queue)
                            renderQueue()
                            renderPlayback()
                            toast("Repeat ${queue.repeat.name.lowercase()}")
                        }
                        3 -> trackDialog("audio")
                        4 -> trackDialog("sub")
                        5 -> pickSubtitle()
                        6 -> showSubtitleSheet(true)
                        7 -> markerDialog()
                        8 -> screenshot()
                        9 -> AlertDialog.Builder(this)
                            .setTitle(queue.current?.title ?: "Media information")
                            .setMessage(engine.metadata())
                            .setPositiveButton("OK", null)
                            .show()
                        10 -> toggleFullscreen()
                        11 -> downloadDialog()
                    }
                }
            }
            .setOnDismissListener { scheduleHide() }
            .show()
    }

    private fun speedDialog() {
        val values = arrayOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0, 4.0)
        val labels = values.map { if (it == it.roundToInt().toDouble()) "${it.roundToInt()}×" else "${it}×" }.toTypedArray()
        val checked = values.indexOfFirst { abs(it - speed) < 0.01 }.coerceAtLeast(3)
        AlertDialog.Builder(this)
            .setTitle("Playback speed")
            .setSingleChoiceItems(labels, checked) { dialog, i ->
                speed = values[i]
                engine.set("speed", speed)
                renderPlayback()
                dialog.dismiss()
            }
            .show()
    }

    private fun trackDialog(type: String) {
        val details = engine.trackDetails(type)
        val choices = if (type == "sub") {
            val anySelected = details.any { it.selected }
            listOf(MPVEngine.TrackInfo("no", "Subtitles Off", !anySelected)) + details
        } else {
            details
        }
        if (choices.isEmpty()) {
            if (type == "sub") {
                AlertDialog.Builder(this)
                    .setTitle("Subtitles")
                    .setItems(arrayOf("Subtitles Off", "Open Subtitle File…", "Subtitle Settings…")) { _, which ->
                        when (which) {
                            0 -> engine.set("sid", "no")
                            1 -> pickSubtitle()
                            2 -> showSubtitleSheet(true)
                        }
                    }
                    .show()
            } else {
                toast("No $type tracks available")
            }
            return
        }
        val checked = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
        val builder = AlertDialog.Builder(this)
            .setTitle(if (type == "sub") "Subtitles" else "Audio tracks")
            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), checked) { dialog, i ->
                engine.set(if (type == "sub") "sid" else "aid", choices[i].id)
                dialog.dismiss()
            }
        if (type == "sub") {
            builder.setNeutralButton("Open File…") { _, _ -> pickSubtitle() }
            builder.setPositiveButton("Settings…") { _, _ -> showSubtitleSheet(true) }
        }
        builder.show()
    }

    private fun volumeDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(8))
        }
        val label = TextView(this).apply {
            text = "Volume ${volume.roundToInt()}%"
            textSize = 14f
            setTextColor(textPrimary)
            setPadding(0, 0, 0, dp(8))
        }
        val slider = SeekBar(this).apply {
            max = 100
            progress = volume.roundToInt()
            contentDescription = "Volume"
        }
        layout.addView(label)
        layout.addView(slider)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, value: Int, user: Boolean) {
                if (user) {
                    volume = value.toDouble()
                    muted = value == 0
                    store.volume = volume
                    engine.set("volume", volume)
                    label.text = "Volume $value%"
                    renderPlayback()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
        val muteSwitch = Switch(this).apply {
            text = "Mute"
            setTextColor(textPrimary)
            isChecked = muted
            setPadding(0, dp(10), 0, 0)
            setOnCheckedChangeListener { _, checked ->
                muted = checked
                engine.set("volume", if (muted) 0.0 else volume)
                renderPlayback()
            }
        }
        layout.addView(muteSwitch)
        AlertDialog.Builder(this)
            .setTitle("Volume")
            .setView(layout)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun markerDialog() {
        val item = queue.current ?: run {
            toast("Load media first")
            return
        }
        val markers = store.markers(item)
        val options = arrayOf(
            "Set Intro End Here (${playbackTime(engine.position)})",
            "Set Outro Start Here (${playbackTime(engine.position)})",
            "Skip Intro (${markers.intro?.let(::playbackTime) ?: "not set"})",
            "Skip Outro (${markers.outro?.let(::playbackTime) ?: "not set"})",
            "Clear Markers"
        )
        AlertDialog.Builder(this)
            .setTitle("Intro / outro")
            .setItems(options) { _, i ->
                when (i) {
                    0 -> store.saveMarkers(item, markers.copy(intro = engine.position))
                    1 -> store.saveMarkers(item, markers.copy(outro = engine.position))
                    2 -> skipIntro()
                    3 -> skipOutro()
                    4 -> store.saveMarkers(item, Markers())
                }
                renderPlayback()
            }
            .show()
    }

    private fun itemOptions(item: QueueItem) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(
                arrayOf(
                    "Play",
                    "Move up",
                    "Move down",
                    "Remove from queue",
                    "Delete file / download…"
                )
            ) { _, i ->
                when (i) {
                    0 -> safely { play(item) }
                    1 -> { queue.move(item.id, -1); store.save(queue); renderQueue(); renderPlayback() }
                    2 -> { queue.move(item.id, 1); store.save(queue); renderQueue(); renderPlayback() }
                    3 -> removeItems(setOf(item.id))
                    4 -> deleteItems(listOf(item))
                }
            }
            .show()
    }

    private fun groupOptions(group: String) {
        val items = queue.items.filter { it.group == group }
        AlertDialog.Builder(this)
            .setTitle(items.firstOrNull()?.groupTitle)
            .setItems(arrayOf("Remove collection from queue", "Delete collection files / downloads…")) { _, i ->
                if (i == 0) removeItems(items.map { it.id }.toSet()) else deleteItems(items)
            }
            .show()
    }

    private fun removeItems(ids: Set<String>) {
        val wasCurrent = queue.currentId in ids
        if (wasCurrent) remember()
        val next = queue.remove(ids)
        if (wasCurrent) {
            requestGeneration++
            pendingPlay = null
            if (next != null) play(next)
            else {
                queue.currentId = null
                engine.stop()
                torrents.pause()
                resolvedSource = null
            }
        }
        store.save(queue)
        renderQueue()
        renderPlayback()
    }

    private fun deleteItems(items: List<QueueItem>) {
        AlertDialog.Builder(this)
            .setTitle("Permanently delete?")
            .setMessage("Android has no app-wide Trash. Deletes selected documents or app-owned torrent files. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                if (items.any { it.id == queue.currentId }) {
                    remember()
                    engine.stop()
                    requestGeneration++
                }
                torrents.pause()
                io.execute {
                    val deleted = mutableSetOf<String>()
                    val errors = mutableListOf<String>()
                    items.forEach { item ->
                        runCatching {
                            if (item.isTorrent) torrents.deleteDownload(item)
                            else {
                                val uri = Uri.parse(item.uri)
                                require(uri.scheme == "content" && android.provider.DocumentsContract.isDocumentUri(this, uri)) {
                                    "Only selected documents and app-owned downloads can be deleted"
                                }
                                check(android.provider.DocumentsContract.deleteDocument(contentResolver, uri)) {
                                    "Document provider refused deletion"
                                }
                            }
                        }.fold({ deleted.add(item.id) }, { errors.add(it.message ?: "Deletion failed") })
                    }
                    main.post {
                        if (destroyed) return@post
                        removeItems(deleted)
                        if (errors.isNotEmpty()) {
                            queue.current?.let { play(it) }
                            showError(errors.joinToString("\n"))
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadDialog() {
        AlertDialog.Builder(this)
            .setTitle("Torrent downloads")
            .setMessage("${torrents.statusLabel()}\n\n${torrents.downloadSummary()}\n\nDownloads are app-owned and retained between launches. Uninstalling removes them.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun screenshot() {
        if (!engine.hasFile || surface.width <= 0 || surface.height <= 0) {
            toast("Load a video first")
            return
        }
        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(surface, bitmap, { result ->
            if (result != PixelCopy.SUCCESS || destroyed) {
                bitmap.recycle()
                if (!destroyed) showError("Could not capture video ($result)")
                return@request
            }
            pendingScreenshot?.recycle()
            pendingScreenshot = bitmap
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "image/png"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, "andrompv-${System.currentTimeMillis()}.png")
            }, SAVE_SCREENSHOT)
        }, main)
    }

    internal fun toggleFullscreen() {
        setFullscreenMode(!fullscreen)
    }

    internal fun setFullscreenMode(enter: Boolean) {
        fullscreen = enter
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { controller ->
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (fullscreen) controller.hide(WindowInsets.Type.systemBars())
                else controller.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (fullscreen) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
        setControlsOverlayVisible(true)
        renderPlayback()
    }

    private fun scheduleHide() {
        main.removeCallbacks(autoHideControls)
        if (engine.hasFile && !engine.paused && !scrubbing && !queueVisible && !subtitleSheetVisible) {
            main.postDelayed(autoHideControls, 3500)
        }
    }

    private fun clearance() {
        if (surfaceReady && ::surface.isInitialized && ::controlsContainer.isInitialized) {
            val occupiedBottom = if (controlsContainer.visibility == View.VISIBLE) {
                controlsContainer.height + insetBottom + dp(12)
            } else {
                0
            }
            safely {
                engine.subtitleClearance(surface.width, surface.height, occupiedBottom)
            }
        }
    }

    private fun safely(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            showError(e.message ?: "Operation failed")
        } catch (e: LinkageError) {
            showError("Native library could not load: ${e.message}")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun showError(message: String) {
        if (destroyed || isFinishing) return
        errorMessage = message
        if (::errorToast.isInitialized) {
            errorToastText.text = message
            errorToast.visibility = View.VISIBLE
        }
    }

    private fun dismissError() {
        errorMessage = null
        if (::errorToast.isInitialized) {
            errorToast.visibility = View.GONE
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        safely {
            engine.attach(holder.surface, surface.width, surface.height)
            surfaceReady = true
            engine.applySubtitles(store.style)
            pendingPlay?.let { play(it) }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        engine.updateSurfaceSize(width, height)
        clearance()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        safely { engine.detach() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        syncOrientationMode(newConfig)
        root.post {
            if (surfaceReady) engine.updateSurfaceSize(surface.width, surface.height)
            applyResponsiveLayout()
            clearance()
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        main.post(ticker)
        if (surfaceReady) pendingPlay?.let { play(it) }
    }

    override fun onStop() {
        foreground = false
        remember()
        store.save(queue)
        main.removeCallbacks(ticker)
        main.removeCallbacks(autoHideControls)
        if (engine.ready) engine.pause(true)
        if (queue.current?.isTorrent == true) torrents.suspendForBackground() else torrents.pause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onDestroy() {
        destroyed = true
        requestGeneration++
        main.removeCallbacksAndMessages(null)
        previews.close()
        engine.release()
        torrents.close()
        io.shutdownNow()
        pendingScreenshot?.recycle()
        pendingScreenshot = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uris = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let(uris::add)
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add)
            }
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf(MediaSupport::isNetwork)?.let {
                uris.add(Uri.parse(it))
            }
        }
        if (uris.isNotEmpty()) enqueueUris(uris.distinct())
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode == SAVE_SCREENSHOT) {
                pendingScreenshot?.recycle()
                pendingScreenshot = null
            }
            return
        }
        when (requestCode) {
            OPEN_MEDIA -> {
                val uris = mutableListOf<Uri>()
                data?.data?.let(uris::add)
                data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                }
                enqueueUris(uris.distinct())
            }
            OPEN_SUBTITLE -> data?.data?.let { uri ->
                val token = requestGeneration
                io.execute {
                    val result = runCatching {
                        val ext = MediaSupport.extension(name(uri)).ifEmpty { "srt" }
                        clearCachedSubtitles()
                        val file = File(cacheDir, "external-${UUID.randomUUID()}.$ext")
                        contentResolver.openInputStream(uri)?.use { input ->
                            file.outputStream().use { it.write(input.readBytesLimited(16 * 1024 * 1024)) }
                        } ?: error("Cannot read subtitle")
                        file
                    }
                    main.post {
                        if (!destroyed && token == requestGeneration) {
                            result.fold(
                                onSuccess = { safely { engine.command("sub-add", it.absolutePath, "select") } },
                                onFailure = { showError(it.message ?: "Subtitle failed") }
                            )
                        }
                    }
                }
            }
            SAVE_SCREENSHOT -> {
                val bitmap = pendingScreenshot ?: return
                pendingScreenshot = null
                val uri = data?.data ?: run {
                    bitmap.recycle()
                    return
                }
                io.execute {
                    val result = runCatching {
                        contentResolver.openOutputStream(uri)?.use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        } ?: error("Cannot write screenshot")
                    }
                    bitmap.recycle()
                    main.post {
                        if (!destroyed) {
                            result.fold(
                                onSuccess = { toast("Screenshot saved") },
                                onFailure = { showError(it.message ?: "Screenshot failed") }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (currentFocus is EditText) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlayback()
            KeyEvent.KEYCODE_MEDIA_NEXT -> queue.next()?.let { play(it) }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> previous()
            KeyEvent.KEYCODE_DPAD_LEFT -> relativeSeek(-10.0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> relativeSeek(10.0)
            KeyEvent.KEYCODE_DPAD_UP -> {
                volume = (volume + 5.0).coerceIn(0.0, 100.0)
                muted = false
                store.volume = volume
                engine.set("volume", volume)
                flashGestureHud("🔊 Volume ${volume.roundToInt()}%")
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                volume = (volume - 5.0).coerceIn(0.0, 100.0)
                if (volume == 0.0) muted = true
                store.volume = volume
                engine.set("volume", if (muted) 0.0 else volume)
                flashGestureHud(if (muted) "🔇 Muted" else "🔊 Volume ${volume.roundToInt()}%")
            }
            KeyEvent.KEYCODE_M -> {
                muted = !muted
                engine.set("volume", if (muted) 0.0 else volume)
            }
            KeyEvent.KEYCODE_LEFT_BRACKET -> {
                speed = (speed - 0.25).coerceAtLeast(0.25)
                engine.set("speed", speed)
            }
            KeyEvent.KEYCODE_RIGHT_BRACKET -> {
                speed = (speed + 0.25).coerceAtMost(4.0)
                engine.set("speed", speed)
            }
            KeyEvent.KEYCODE_BACKSLASH -> {
                speed = 1.0
                engine.set("speed", speed)
            }
            KeyEvent.KEYCODE_F -> toggleFullscreen()
            KeyEvent.KEYCODE_ESCAPE -> when {
                subtitleSheetVisible -> showSubtitleSheet(false)
                queueVisible -> setSidebarVisible(false)
                fullscreen -> setFullscreenMode(false)
                else -> return super.onKeyDown(keyCode, event)
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        renderPlayback()
        return true
    }

    private fun clearCachedSubtitles() {
        cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("external-")) file.delete()
        }
    }

    companion object {
        private const val OPEN_MEDIA = 1
        private const val OPEN_SUBTITLE = 2
        private const val SAVE_SCREENSHOT = 3
    }
}

fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "File exceeds the ${limit / 1024 / 1024} MB limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
