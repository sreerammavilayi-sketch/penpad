package com.example.pentablet

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        val d = resources.displayMetrics.density
        val root = FrameLayout(this)
        val pad = PadView(this)
        root.addView(pad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val edit = EditText(this).apply {
            visibility = View.GONE
            hint = "Type here - appears on the PC"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            textSize = 20f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            maxLines = 4
        }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = (60 * d).toInt()
        lp.leftMargin = (8 * d).toInt()
        lp.rightMargin = (120 * d).toInt()
        root.addView(edit, lp)
        pad.attachEditor(edit)
        setContentView(root)
    }
}

class PadView(ctx: Context) : View(ctx) {
    companion object {
        const val LANG = "en-US"      // e.g. "ml-IN", "ta-IN", "hi-IN"
        const val LIVE_MS = 150L      // after a stroke ends -> update the live text
        const val COMMIT_MS = 1200L   // pause -> word finished, start a new one
    }

    private val q = LinkedBlockingQueue<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val dp = ctx.resources.displayMetrics.density
    private val drawSlop = ViewConfiguration.get(ctx).scaledTouchSlop * 1.5f
    private val moveSlop = 4f * dp
    private val label = Paint().apply { color = Color.WHITE; textSize = 56f; isAntiAlias = true }
    private val small = Paint().apply { color = Color.LTGRAY; textSize = 40f; isAntiAlias = true }
    private val btn = Paint().apply { color = Color.rgb(60, 60, 60) }
    private val pathPaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 7f
        isAntiAlias = true; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    @Volatile private var drawMode = false
    @Volatile private var tool = 0            // 0 = PEN (freestyle), 1 = TEXT (handwriting -> live words), 2 = KEYS (phone keyboard)
    private val textTool: Boolean get() = tool == 1
    private val keyTool: Boolean get() = tool == 2

    private var downX = 0f; private var downY = 0f; private var downT = 0L
    private var lastX = 0f; private var lastY = 0f
    private var moved = false
    private var ignoring = false
    private var lastTapT = 0L
    private var dotX = 0f; private var dotY = 0f

    // TEXT tool state
    private var recognizer: DigitalInkRecognizer? = null
    @Volatile private var modelReady = false
    private var status = ""
    private val strokes = ArrayList<Ink.Stroke>()
    private var strokeBuilder: Ink.Stroke.Builder? = null
    private val paths = ArrayList<Path>()
    private var curPath: Path? = null
    private var seq = 0

    private val liveRunnable = Runnable { recognizeLive() }
    private val commitRunnable = Runnable { commitText() }
    private val dotRunnable = Runnable {
        if (textTool) {
            val t = System.currentTimeMillis()
            beginStroke(dotX, dotY, t); extendStroke(dotX + 1f, dotY, t + 10); finishStroke()
        } else {
            send("D", dotX, dotY, 0.6f); q.offer("U\n")
        }
    }

    @Volatile private var conn = "Searching for PC..."
    private fun setConn(s: String) { conn = s; postInvalidate() }

    // The PC app announces itself by UDP broadcast; we learn its address from that.
    private fun discover(): Pair<String, Int> {
        DatagramSocket(null).use { ds ->
            ds.reuseAddress = true
            ds.bind(InetSocketAddress(5556))
            val buf = ByteArray(256)
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                ds.receive(p)
                val t = String(p.data, 0, p.length).split(",")
                if (t.size >= 2 && t[0] == "PENPAD1")
                    return Pair(p.address.hostAddress ?: continue, t[1].toInt())
            }
        }
    }

    init {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("penpad")?.apply { setReferenceCounted(false); acquire() }
        val devId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        thread(isDaemon = true) {
            while (true) {
                try {
                    setConn("Searching for PC...")
                    val (ip, port) = discover()
                    setConn("Connecting...")
                    Socket().use { s ->
                        s.connect(InetSocketAddress(ip, port), 3000)
                        s.tcpNoDelay = true
                        s.soTimeout = 70000
                        val o = s.getOutputStream()
                        o.write("HELLO,$devId,${Build.MODEL}\n".toByteArray()); o.flush()
                        setConn("Approve this phone on the PC...")
                        val reply = s.getInputStream().bufferedReader().readLine()
                        if (reply != "OK") {
                            setConn("PC refused this phone"); Thread.sleep(5000); return@use
                        }
                        s.soTimeout = 0
                        setConn("Connected")
                        q.clear()
                        o.write("M,${if (drawMode) "draw" else "move"}\n".toByteArray()); o.flush()
                        while (true) {
                            val m = q.poll(2, TimeUnit.SECONDS) ?: "H\n"   // heartbeat
                            o.write(m.toByteArray()); o.flush()
                        }
                    }
                } catch (e: Exception) { }
                Thread.sleep(1000)
            }
        }
        setupRecognizer()
    }

    private fun setupRecognizer() {
        try {
            val id = DigitalInkRecognitionModelIdentifier.fromLanguageTag(LANG) ?: return
            val model = DigitalInkRecognitionModel.builder(id).build()
            recognizer = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
            status = "Downloading handwriting model (internet needed once)..."
            RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build())
                .addOnSuccessListener { modelReady = true; status = ""; postInvalidate() }
                .addOnFailureListener { status = "Model download failed - check internet"; postInvalidate() }
        } catch (e: Exception) { status = "Recognizer error: ${e.message}" }
    }


    // ---------- PEN tool: raw phone pixels, the PC maps them around the cursor ----------
    private fun send(c: String, px: Float, py: Float, p: Float) {
        q.offer("$c,$px,$py,$p\n")
    }

    // ---------- TEXT tool ----------
    private fun b64(t: String) = Base64.encodeToString(t.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun buildInk(): Ink {
        val b = Ink.builder()
        for (s in strokes) b.addStroke(s)
        return b.build()
    }

    private fun beginStroke(x: Float, y: Float, t: Long) {
        strokeBuilder = Ink.Stroke.builder().apply { addPoint(Ink.Point.create(x, y, t)) }
        curPath = Path().apply { moveTo(x, y) }
        handler.removeCallbacks(commitRunnable)
    }

    private fun extendStroke(x: Float, y: Float, t: Long) {
        strokeBuilder?.addPoint(Ink.Point.create(x, y, t))
        curPath?.lineTo(x, y)
        invalidate()
    }

    private fun finishStroke() {
        strokeBuilder?.let { strokes.add(it.build()) }
        strokeBuilder = null
        curPath?.let { paths.add(it) }
        curPath = null
        handler.removeCallbacks(liveRunnable); handler.postDelayed(liveRunnable, LIVE_MS)
        handler.removeCallbacks(commitRunnable); handler.postDelayed(commitRunnable, COMMIT_MS)
        invalidate()
    }

    // Recognise everything written so far and send the whole text; the PC fixes only what changed.
    private fun recognizeLive() {
        if (strokes.isEmpty() || !modelReady) return
        val my = ++seq
        recognizer?.recognize(buildInk())?.addOnSuccessListener { r ->
            if (my != seq) return@addOnSuccessListener
            val t = r.candidates.firstOrNull()?.text ?: return@addOnSuccessListener
            q.offer("T,${b64(t)}\n")
        }
    }

    private fun clearInk() {
        handler.removeCallbacks(liveRunnable); handler.removeCallbacks(commitRunnable)
        strokes.clear(); strokeBuilder = null; paths.clear(); curPath = null
        invalidate()
    }

    // Finish the current word: send final text, then "C" (PC adds a space), clear the phone screen.
    private fun commitText(after: (() -> Unit)? = null) {
        val rec = recognizer
        if (strokes.isEmpty() || !modelReady || rec == null) {
            clearInk(); after?.invoke(); return
        }
        val ink = buildInk()
        clearInk()
        seq++
        val finish = { q.offer("C\n"); after?.invoke(); Unit }
        rec.recognize(ink)
            .addOnSuccessListener { r ->
                r.candidates.firstOrNull()?.text?.let { q.offer("T,${b64(it)}\n") }
                finish()
            }
            .addOnFailureListener { finish() }
    }

    // ---------- modes ----------
    private fun toggleMove() {                 // double-tap: MOVE <-> DRAW
        val flip = {
            drawMode = !drawMode
            q.offer("M,${if (drawMode) "draw" else "move"}\n")
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
        if (drawMode && textTool) commitText { flip() } else flip()
    }

    // ---------- KEYS tool: the phone keyboard types on the PC ----------
    private var edit: EditText? = null
    private var suppress = false

    fun attachEditor(e: EditText) {
        edit = e
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppress || !keyTool) return
                val t = s?.toString() ?: ""
                q.offer("T,${b64(t)}\n")          // PC types only what changed
                if (t.length > 300 && (t.last() == ' ' || t.last() == '\n')) {
                    q.offer("N\n")                // long text: start a fresh box, keep what is typed
                    e.post { clearEdit() }
                }
            }
        })
        e.setOnKeyListener { _, keyCode, ev ->
            if (keyCode == KeyEvent.KEYCODE_DEL && ev.action == KeyEvent.ACTION_DOWN && e.text.isEmpty()) {
                q.offer("B\n"); true               // backspace into text typed earlier
            } else false
        }
    }

    private fun clearEdit() { suppress = true; edit?.setText(""); suppress = false }

    private fun openKeys() {
        val e = edit ?: return
        if (drawMode) { drawMode = false; q.offer("M,move\n") }
        clearEdit()
        e.visibility = View.VISIBLE
        e.requestFocus()
        e.post {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(e, 0)
        }
    }

    private fun closeKeys() {
        val e = edit ?: return
        q.offer("N\n")
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(e.windowToken, 0)
        e.clearFocus()
        e.visibility = View.GONE
    }

    private fun toggleTool() {                 // corner button: PEN -> TEXT -> KEYS -> PEN
        q.offer("U\n")
        val wasKeys = keyTool
        val flip = {
            if (wasKeys) closeKeys()
            tool = (tool + 1) % 3
            if (keyTool) openKeys()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
        if (drawMode && textTool) commitText { flip() } else { clearInk(); flip() }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(when { keyTool -> Color.rgb(30, 50, 110); drawMode -> Color.rgb(20, 90, 40); else -> Color.DKGRAY })
        c.drawText(when { keyTool -> "KEYS  (drag = move, tap = click)"; drawMode -> "DRAW  (double-tap = move)"; else -> "MOVE  (double-tap = draw)" }, 40f, 90f, label)
        if (textTool && status.isNotEmpty()) c.drawText(status, 40f, 150f, small)
        c.drawText(conn, 40f, height - 40f, small)
        for (p in paths) c.drawPath(p, pathPaint)
        curPath?.let { c.drawPath(it, pathPaint) }
        c.drawRect(width - 220f, 0f, width.toFloat(), 130f, btn)
        c.drawText(when (tool) { 1 -> "TEXT"; 2 -> "KEYS"; else -> "PEN" }, width - 200f, 90f, label)
    }

    // One finger sample. MOVE mode: touchpad deltas. DRAW mode: pen stroke or text stroke.
    private fun point(x: Float, y: Float, t: Long, pressure: Float) {
        val s = if (drawMode) drawSlop else moveSlop
        if (!moved && Math.hypot((x - downX).toDouble(), (y - downY).toDouble()) > s) {
            moved = true
            if (drawMode) {
                if (textTool) beginStroke(downX, downY, downT) else send("D", downX, downY, pressure)
            } else {
                lastX = x; lastY = y          // start from here: no jump
            }
        }
        if (moved) {
            if (drawMode) {
                if (textTool) extendStroke(x, y, t) else send("V", x, y, pressure)
            } else q.offer("R,${x - lastX},${y - lastY}\n")
        }
        lastX = x; lastY = y
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pressure = if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS)
            e.pressure.coerceIn(0f, 1f) else 0.6f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.x > width - 220 && e.y < 130) { ignoring = true; toggleTool(); return true }
                ignoring = false
                downX = e.x; downY = e.y; downT = e.eventTime
                lastX = e.x; lastY = e.y; moved = false
                if (drawMode && textTool) handler.removeCallbacks(commitRunnable)
            }
            MotionEvent.ACTION_MOVE -> {
                if (ignoring) return true
                if (drawMode) for (i in 0 until e.historySize)       // keep corners sharp (squares, arrows)
                    point(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalEventTime(i), pressure)
                point(e.x, e.y, e.eventTime, pressure)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (ignoring) { ignoring = false; return true }
                if (moved) {
                    if (drawMode) {
                        if (textTool) finishStroke() else q.offer("U\n")
                    }
                } else if (e.eventTime - downT < 250 && keyTool) {   // KEYS: tap = click
                    q.offer("L\n"); clearEdit()
                } else if (e.eventTime - downT < 250) {
                    val now = e.eventTime
                    if (now - lastTapT < 350) {           // double tap
                        handler.removeCallbacks(dotRunnable)
                        lastTapT = 0
                        toggleMove()
                    } else {
                        lastTapT = now
                        if (drawMode) {
                            dotX = e.x; dotY = e.y
                            handler.postDelayed(dotRunnable, 350)
                        }
                    }
                } else if (drawMode && textTool && strokes.isNotEmpty()) {
                    handler.postDelayed(commitRunnable, COMMIT_MS)
                }
            }
        }
        return true
    }
}
