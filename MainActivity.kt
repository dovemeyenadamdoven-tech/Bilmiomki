package com.example.altyazidublaj

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MIN_INTERVAL_MS = 250L   // iki OCR arasındaki en kısa süre
        private const val STABLE_READS = 2         // aynı metin kaç kez okununca çevrilsin
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: RegionOverlayView
    private lateinit var tvStatus: TextView
    private lateinit var tvOcr: TextView
    private lateinit var tvTr: TextView
    private lateinit var btnToggle: Button
    private lateinit var seekSpeed: SeekBar

    private var imageAnalysis: ImageAnalysis? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private lateinit var translator: Translator
    private var translatorReady = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @Volatile private var running = false
    @Volatile private var busy = false
    @Volatile private var lastRunMs = 0L

    private var candidate = ""
    private var candidateCount = 0
    private var lastHandled = ""
    private var seq = 0
    private var speechRate = 1.2f

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else tvStatus.text = "Kamera izni gerekli."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.preview)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        overlay = findViewById(R.id.overlay)
        tvStatus = findViewById(R.id.tvStatus)
        tvOcr = findViewById(R.id.tvOcr)
        tvTr = findViewById(R.id.tvTr)
        btnToggle = findViewById(R.id.btnToggle)
        seekSpeed = findViewById(R.id.seekSpeed)

        val prefs = getSharedPreferences("ayar", MODE_PRIVATE)
        overlay.setFractions(
            prefs.getFloat("l", 0.1f), prefs.getFloat("t", 0.65f),
            prefs.getFloat("r", 0.9f), prefs.getFloat("b", 0.85f)
        )
        overlay.onChanged = {
            val f = overlay.fractions()
            prefs.edit().putFloat("l", f[0]).putFloat("t", f[1]).putFloat("r", f[2]).putFloat("b", f[3]).apply()
        }

        val progress = prefs.getInt("hiz", 4)
        seekSpeed.progress = progress
        speechRate = 0.8f + progress * 0.1f
        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                speechRate = 0.8f + p * 0.1f
                prefs.edit().putInt("hiz", p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        btnToggle.setOnClickListener {
            running = !running
            btnToggle.text = if (running) "Durdur" else "Başlat"
            if (running) {
                candidate = ""
                candidateCount = 0
                lastHandled = ""
            } else {
                tts?.stop()
            }
        }

        setupTts()
        setupTranslator()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("tr", "TR"))
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) {
                    tvStatus.text = "Türkçe ses yok. Telefon Ayarları > Metin okuma bölümünden Türkçe ses verisini indir."
                }
            } else {
                tvStatus.text = "Ses motoru başlatılamadı."
            }
        }
    }

    private fun setupTranslator() {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        translator = Translation.getClient(options)
        lifecycle.addObserver(translator)
        tvStatus.text = "Çeviri modeli indiriliyor (ilk seferde internet gerekir)..."
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build())
            .addOnSuccessListener {
                translatorReady = true
                tvStatus.text = "Hazır. Çerçeveyi altyazının üstüne getir, Başlat'a bas."
            }
            .addOnFailureListener {
                tvStatus.text = "Çeviri modeli inmedi: ${it.message}"
            }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(ratio)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { analyze(it) }
            imageAnalysis = analysis

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalysis?.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
    }

    // ---- Kare işleme (arka plan thread'i) ----

    private fun analyze(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (!running || busy || now - lastRunMs < MIN_INTERVAL_MS) {
            proxy.close()
            return
        }
        lastRunMs = now
        busy = true

        var crop: Bitmap? = null
        try {
            val full = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            val upright = if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                Bitmap.createBitmap(full, 0, 0, full.width, full.height, m, true)
            } else full
            crop = cropToRegion(upright)
        } catch (e: Exception) {
            crop = null
        } finally {
            proxy.close()
        }

        val c = crop
        if (c == null) {
            busy = false
            return
        }
        recognizer.process(InputImage.fromBitmap(c, 0))
            .addOnSuccessListener { handleText(it.text) }
            .addOnCompleteListener { busy = false }
    }

    /** Ekrandaki çerçeveyi kamera görüntüsündeki bölgeye çevirir (önizleme FIT_CENTER). */
    private fun cropToRegion(bmp: Bitmap): Bitmap? {
        val vw = overlay.width.toFloat()
        val vh = overlay.height.toFloat()
        if (vw <= 0f || vh <= 0f) return null
        val scale = min(vw / bmp.width, vh / bmp.height)
        val offX = (vw - bmp.width * scale) / 2f
        val offY = (vh - bmp.height * scale) / 2f
        val r = RectF(overlay.rect)
        val l = ((r.left - offX) / scale).toInt().coerceIn(0, bmp.width - 1)
        val t = ((r.top - offY) / scale).toInt().coerceIn(0, bmp.height - 1)
        val rr = ((r.right - offX) / scale).toInt().coerceIn(l + 1, bmp.width)
        val bb = ((r.bottom - offY) / scale).toInt().coerceIn(t + 1, bmp.height)
        if (rr - l < 10 || bb - t < 10) return null
        return Bitmap.createBitmap(bmp, l, t, rr - l, bb - t)
    }

    // ---- Metin işleme (ana thread) ----

    private fun handleText(raw: String) {
        val text = raw.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        tvOcr.text = text
        if (text.count { it.isLetter() } < 3) return

        if (similar(text, candidate)) {
            candidateCount++
        } else {
            candidate = text
            candidateCount = 1
        }
        if (candidateCount >= STABLE_READS && !similar(candidate, lastHandled)) {
            lastHandled = candidate
            translateAndSpeak(candidate)
        }
    }

    private fun translateAndSpeak(text: String) {
        if (!translatorReady) {
            tvStatus.text = "Çeviri modeli henüz hazır değil..."
            return
        }
        val id = ++seq
        translator.translate(text)
            .addOnSuccessListener { tr ->
                if (id != seq) return@addOnSuccessListener // daha yeni altyazı geldi, eskisini at
                tvTr.text = tr
                speak(tr)
            }
            .addOnFailureListener { tvStatus.text = "Çeviri hatası: ${it.message}" }
    }

    private fun speak(t: String) {
        if (!ttsReady || !running) return
        tts?.setSpeechRate(speechRate)
        // QUEUE_FLUSH: ses geride kalırsa eskiyi keser, en yeni altyazıyı okur
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "altyazi")
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun similar(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val maxLen = max(x.length, y.length)
        if (abs(x.length - y.length) > maxLen * 0.2) return false
        return levenshtein(x, y) <= maxLen * 0.15
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        recognizer.close()
        analysisExecutor.shutdown()
    }
}
