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
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
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
        setContentView(PadView(this))
    }
}

class PadView(ctx: Context) : View(ctx) {
    companion object {
        const val LANG = "en-US"      // e.g. "ml-IN", "ta-IN", "hi-IN"
        const val PAUSE_MS = 1000L    // pause after last stroke -> recognise
    }

    private val q = LinkedBlockingQueue<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 1.5f
    private val label = Paint().apply { color = Color.WHITE; textSize = 56f; isAntiAlias = true }
    private val small = Paint().apply { color = Color.LTGRAY; textSize = 40f; isAntiAlias = true }
    private val btn = Paint().apply { color = Color.rgb(60, 60, 60) }
    private val pathPaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 7f
        isAntiAlias = true; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    @Volatile private var drawMode = false
    @Volatile private var textOut = false     // false = INK (pen strokes), true = TEXT (recognise)

    private var downX = 0f; private var downY = 0f; private var downT = 0L
    private var lastX = 0f; private var lastY = 0f
    private var moved = false
    private var ignoring = false
    private var lastTapT = 0L
    private var dotX = 0f; private var dotY = 0f

    // TEXT mode state
    private var recognizer: DigitalInkRecognizer? = null
    @Volatile private var modelReady = false
    private var status = ""
    private var inkBuilder = Ink.builder()
    private var strokeBuilder: Ink.Stroke.Builder? = null
    private var strokeCount = 0
    private val paths = ArrayList<Path>()
    private var curPath: Path? = null

    private val recognizeRunnable = Runnable { recognizeNow() }
    private val dotRunnable = Runnable {
        if (textOut) {
            beginStroke(dotX, dotY, System.currentTimeMillis())
            extendStroke(dotX + 1f, dotY, System.currentTimeMillis() + 10)
            finishStroke()
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

    // ---------- INK output ----------
    private fun send(c: String, px: Float, py: Float, p: Float) {
        val x = (px / width).coerceIn(0f, 1f)
        val y = (py / height).coerceIn(0f, 1f)
        q.offer("$c,$x,$y,$p\n")
    }

    // ---------- TEXT output ----------
    private fun beginStroke(x: Float, y: Float, t: Long) {
        strokeBuilder = Ink.Stroke.builder().apply { addPoint(Ink.Point.create(x, y, t)) }
        curPath = Path().apply { moveTo(x, y) }
        handler.removeCallbacks(recognizeRunnable)
    }

    private fun extendStroke(x: Float, y: Float, t: Long) {
        strokeBuilder?.addPoint(Ink.Point.create(x, y, t))
        curPath?.lineTo(x, y)
        invalidate()
    }

    private fun finishStroke() {
        strokeBuilder?.let { inkBuilder.addStroke(it.build()); strokeCount++ }
        strokeBuilder = null
        curPath?.let { paths.add(it) }
        curPath = null
        handler.removeCallbacks(recognizeRunnable)
        handler.postDelayed(recognizeRunnable, PAUSE_MS)
        invalidate()
    }

    private fun recognizeNow() {
        handler.removeCallbacks(recognizeRunnable)
        if (strokeCount == 0 || !modelReady) return
        val ink = inkBuilder.build()
        inkBuilder = Ink.builder(); strokeCount = 0; paths.clear(); invalidate()
        recognizer?.recognize(ink)?.addOnSuccessListener { r ->
            val t = r.candidates.firstOrNull()?.text
            if (!t.isNullOrEmpty())
                q.offer("T,${Base64.encodeToString(t.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}\n")
        }
    }

    private fun clearInk() {
        handler.removeCallbacks(recognizeRunnable)
        inkBuilder = Ink.builder(); strokeBuilder = null; strokeCount = 0
        paths.clear(); curPath = null
    }

    // ---------- modes ----------
    private fun toggleMove() {                 // double-tap: MOVE <-> DRAW
        if (textOut) recognizeNow()
        drawMode = !drawMode
        q.offer("M,${if (drawMode) "draw" else "move"}\n")
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun toggleOutput() {               // corner button: INK <-> TEXT
        q.offer("U\n")
        clearInk()
        textOut = !textOut
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(if (drawMode) Color.rgb(20, 90, 40) else Color.DKGRAY)
        c.drawText(if (drawMode) "DRAW  (double-tap = move)" else "MOVE  (double-tap = draw)", 40f, 90f, label)
        c.drawText(conn, 40f, height - 40f, small)
        if (textOut && status.isNotEmpty()) c.drawText(status, 40f, 150f, small)
        for (p in paths) c.drawPath(p, pathPaint)
        curPath?.let { c.drawPath(it, pathPaint) }
        c.drawRect(width - 220f, 0f, width.toFloat(), 130f, btn)
        c.drawText(if (textOut) "TEXT" else "INK", width - 190f, 90f, label)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pressure = if (e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS)
            e.pressure.coerceIn(0f, 1f) else 0.6f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.x > width - 220 && e.y < 130) { ignoring = true; toggleOutput(); return true }
                ignoring = false
                downX = e.x; downY = e.y; downT = e.eventTime
                lastX = e.x; lastY = e.y; moved = false
                if (drawMode && textOut) handler.removeCallbacks(recognizeRunnable)
            }
            MotionEvent.ACTION_MOVE -> {
                if (ignoring) return true
                if (!moved && Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > slop) {
                    moved = true
                    if (drawMode) {
                        if (textOut) beginStroke(downX, downY, downT)
                        else send("D", downX, downY, pressure)   // stroke starts at the real down point
                    }
                }
                if (moved) {
                    if (drawMode) {
                        if (textOut) extendStroke(e.x, e.y, e.eventTime)
                        else send("V", e.x, e.y, pressure)
                    } else q.offer("R,${e.x - lastX},${e.y - lastY}\n")
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (ignoring) { ignoring = false; return true }
                if (moved) {
                    if (drawMode) {
                        if (textOut) finishStroke() else q.offer("U\n")
                    }
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
                } else if (drawMode && textOut && strokeCount > 0) {
                    handler.postDelayed(recognizeRunnable, PAUSE_MS)
                }
            }
        }
        return true
    }
}
