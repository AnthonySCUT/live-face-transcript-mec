package edu.scut.livetranscript

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Decodes the first audio track, yielding 16 kHz mono PCM and media end timestamps. */
object VideoAudioDecoder {
    fun decode(context: Context, uri: Uri, cancelled: () -> Boolean, onPcm: (ShortArray, Long) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("视频没有音轨，请选择带声音的视频")
            val format = extractor.getTrackFormat(index)
            extractor.selectTrack(index)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var inputEnded = false
            var outputEnded = false
            var converter: PcmResampler? = null
            var rate = 0
            var channels = 0
            val info = MediaCodec.BufferInfo()
            var lastProgress = android.os.SystemClock.elapsedRealtime()
            while (!outputEnded && !cancelled()) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        lastProgress = android.os.SystemClock.elapsedRealtime()
                        val buffer = requireNotNull(decoder.getInputBuffer(inputIndex))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    lastProgress = android.os.SystemClock.elapsedRealtime()
                    val output = decoder.outputFormat
                    val encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    check(encoding == AudioFormat.ENCODING_PCM_16BIT) { "解码器未输出 16-bit PCM，暂不支持此音轨" }
                    rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    converter = PcmResampler(rate, channels)
                } else if (outputIndex >= 0) {
                    try {
                        if (info.size > 0) {
                            check(info.size % (2 * channels.coerceAtLeast(1)) == 0) { "解码 PCM 不完整" }
                            val buffer = requireNotNull(decoder.getOutputBuffer(outputIndex))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = buffer.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val samples = ShortArray(shorts.remaining()).also { shorts.get(it) }
                            val mono = requireNotNull(converter).convert(samples)
                            val endMs = info.presentationTimeUs / 1000 + samples.size * 1000L / channels / rate
                            if (mono.isNotEmpty()) onPcm(mono, endMs.coerceAtLeast(0L))
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(outputIndex, false); lastProgress = android.os.SystemClock.elapsedRealtime() }
                }
                check(android.os.SystemClock.elapsedRealtime() - lastProgress < 20_000) { "音轨解码超过 20 秒没有进展" }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) { }
            codec?.release()
            extractor.release()
        }
    }
}
