package com.delrogue.grooverider.source

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a content URI (MP3, WAV, M4A, FLAC, …) to interleaved float32 PCM at
 * the file's native sample rate, using the platform MediaExtractor + MediaCodec.
 * Capped at [maxSeconds] so a huge file can't exhaust memory (spec: 60 s source).
 */
object AudioDecoder {

    private const val TIMEOUT_US = 10_000L

    fun decode(context: Context, uri: Uri, maxSeconds: Double = 60.0): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: run { extractor.release(); throw IllegalArgumentException("No audio track") }

        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val out = GrowableFloats()
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        var maxFrames = Long.MAX_VALUE

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(inBuf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outIndex >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        appendPcm(buf, codec.outputFormat, out)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (out.size.toLong() >= maxFrames * channels) sawOutputEOS = true
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    maxFrames = (maxSeconds * sampleRate).toLong()
                }
            }
        }

        codec.stop(); codec.release(); extractor.release()

        var samples = out.toArray()
        // enforce the cap precisely (interleaved)
        val capSamples = ((maxSeconds * sampleRate).toLong() * channels)
        if (samples.size > capSamples) samples = samples.copyOf(capSamples.toInt())
        return DecodedAudio(samples, channels, sampleRate)
    }

    /** Converts one output buffer to float, handling 16-bit and float PCM. */
    private fun appendPcm(buf: ByteBuffer, format: MediaFormat, out: GrowableFloats) {
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING))
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) else 2 // ENCODING_PCM_16BIT
        buf.order(ByteOrder.LITTLE_ENDIAN)
        when (encoding) {
            4 -> { // ENCODING_PCM_FLOAT
                val fb = buf.asFloatBuffer()
                while (fb.hasRemaining()) out.add(fb.get())
            }
            else -> { // 16-bit
                val sb = buf.asShortBuffer()
                while (sb.hasRemaining()) out.add(sb.get() / 32768f)
            }
        }
    }

    /** Growable primitive float buffer — avoids boxing millions of samples. */
    private class GrowableFloats {
        private var data = FloatArray(1 shl 16)
        var size = 0; private set
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
