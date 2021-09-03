package com.github.skgmn.webpdecoder

import android.graphics.*
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import coil.bitmap.BitmapPool
import com.github.skgmn.webpdecoder.libwebp.LibWebPAnimatedDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

internal class AnimatedWebPDrawable(
    private val decoder: LibWebPAnimatedDecoder,
    private val bitmapPool: BitmapPool
) : Drawable(), Animatable {
    private val paint by lazy(LazyThreadSafetyMode.NONE) { Paint(Paint.FILTER_BITMAP_FLAG) }
    private val decodeChannel by lazy {
        Channel<LibWebPAnimatedDecoder.DecodeFrameResult>(1)
    }
    private var decodeJob: Job? = null
    private var frameWaitingJob: Job? = null
    private var pendingDecodeResult: LibWebPAnimatedDecoder.DecodeFrameResult? = null

    private var currentBitmap: Bitmap? = null
        set(value) {
            if (field !== value) {
                field?.let { bitmapPool.put(it) }
                field = value
            }
        }

    @OptIn(DelicateCoroutinesApi::class)
    override fun draw(canvas: Canvas) {
        val time = SystemClock.uptimeMillis()
        val decodeFrameResult = pendingDecodeResult?.also {
            pendingDecodeResult = null
        } ?: decodeChannel.tryReceive().getOrNull()
        if (decodeFrameResult == null) {
            currentBitmap?.let {
                canvas.drawBitmap(it, null, bounds, paint)
            }
            if (isRunning && frameWaitingJob == null) {
                frameWaitingJob = GlobalScope.launch(Dispatchers.Main.immediate) {
                    val result = decodeChannel.receive()
                    pendingDecodeResult = result
                    invalidateSelf()
                    frameWaitingJob = null
                }
            }
        } else {
            val backgroundColor = decoder.backgroundColor
            if (Color.alpha(backgroundColor) != 0) {
                canvas.drawColor(backgroundColor)
            }
            canvas.drawBitmap(decodeFrameResult.bitmap, null, bounds, paint)
            currentBitmap = decodeFrameResult.bitmap
            scheduleSelf({
                invalidateSelf()
            }, time + decodeFrameResult.frameLengthMs)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int {
        return if (decoder.hasAlpha) {
            PixelFormat.TRANSLUCENT
        } else {
            PixelFormat.OPAQUE
        }
    }

    override fun getIntrinsicWidth(): Int {
        return decoder.width
    }

    override fun getIntrinsicHeight(): Int {
        return decoder.height
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun start() {
        if (decodeJob != null) return
        decodeJob = GlobalScope.launch(Dispatchers.Default) {
            val loopCount = decoder.loopCount
            var i = 0
            while (loopCount == 0 || i < loopCount) {
                if (!isActive) {
                    return@launch
                }
                decoder.reset()
                while (isActive && decoder.hasNextFrame()) {
                    val reuseBitmap = bitmapPool.getDirtyOrNull(
                        decoder.width,
                        decoder.height,
                        Bitmap.Config.ARGB_8888
                    )
                    val result = decoder.decodeNextFrame(reuseBitmap)
                    if (result == null || result.bitmap !== reuseBitmap) {
                        reuseBitmap?.let { bitmapPool.put(it) }
                    }
                    decodeChannel.send(result ?: break)
                }
                ++i
            }
        }
    }

    override fun stop() {
        frameWaitingJob?.cancel()
        frameWaitingJob = null

        decodeJob?.cancel()
        decodeJob = null
    }

    override fun isRunning(): Boolean {
        return decodeJob?.isActive == true
    }
}