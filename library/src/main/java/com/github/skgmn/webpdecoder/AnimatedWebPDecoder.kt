package com.github.skgmn.webpdecoder

import android.graphics.Bitmap
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import com.github.skgmn.webpdecoder.libwebp.LibWebPAnimatedDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class AnimatedWebPDecoder(
    private val source: ImageSource,
    private val options: Options,
    //private val size: Size
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val drawable = withContext(Dispatchers.IO) {
            val bytes = source.sourceOrNull()?.readByteArray() ?: return@withContext null
            // really wanted to avoid whole bytes copying but it's inevitable
            // unless the size of source is provided in advance

            val byteBuffer = ByteBuffer.allocateDirect(bytes.size).put(bytes)
            val decoder = LibWebPAnimatedDecoder.create(byteBuffer/*, options.premultipliedAlpha*/)
            val bitmap = Bitmap.createBitmap(decoder.width, decoder.height, Bitmap.Config.ARGB_8888)
            val firstFrame = if (decoder.hasNextFrame()) {
                    /* pool.getDirtyOrNull(
                    decoder.width,
                    decoder.height,
                    Bitmap.Config.ARGB_8888
                )*/
                decoder.decodeNextFrame(bitmap)
            } else {
                null
            }
            AnimatedWebPDrawable(decoder, bitmap, firstFrame)
        }

        return drawable?.let { DecodeResult(it, false) }
    }

    companion object {
        class Factory : Decoder.Factory {
            override fun create(
                result: SourceResult,
                options: Options,
                imageLoader: ImageLoader
            ): Decoder? {
                val headerBytes =
                    result.source.source().peek().readByteArray(WebPSupportStatus.HEADER_SIZE)
                if (!(WebPSupportStatus.isWebpHeader(headerBytes, 0, headerBytes.size) &&
                            WebPSupportStatus.isAnimatedWebpHeader(headerBytes, 0))
                ) return null

                return AnimatedWebPDecoder(result.source, options)
            }

            override fun equals(other: Any?) = other is Factory

            override fun hashCode() = javaClass.hashCode()
        }
    }
}