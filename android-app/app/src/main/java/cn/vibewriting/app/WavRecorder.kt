package cn.vibewriting.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

class WavRecorder(private val outputFile: File) {
    private val sampleRate = 16_000
    private val recording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    fun start() {
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minimum, 4096)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "无法初始化麦克风" }
        audioRecord = recorder
        recording.set(true)
        recorder.startRecording()

        worker = Thread {
            FileOutputStream(outputFile).use { stream ->
                stream.write(ByteArray(44))
                val buffer = ByteArray(bufferSize)
                while (recording.get()) {
                    val count = recorder.read(buffer, 0, buffer.size)
                    if (count > 0) stream.write(buffer, 0, count)
                }
            }
            writeHeader(outputFile)
        }.apply {
            name = "vibe-writing-recorder"
            start()
        }
    }

    fun stop(): File {
        recording.set(false)
        runCatching { audioRecord?.stop() }
        worker?.join(1500)
        audioRecord?.release()
        audioRecord = null
        worker = null
        return outputFile
    }

    private fun writeHeader(file: File) {
        val dataLength = file.length() - 44
        val byteRate = sampleRate * 2
        RandomAccessFile(file, "rw").use { output ->
            output.seek(0)
            output.writeBytes("RIFF")
            writeLittleEndian(output, dataLength + 36, 4)
            output.writeBytes("WAVEfmt ")
            writeLittleEndian(output, 16, 4)
            writeLittleEndian(output, 1, 2)
            writeLittleEndian(output, 1, 2)
            writeLittleEndian(output, sampleRate.toLong(), 4)
            writeLittleEndian(output, byteRate.toLong(), 4)
            writeLittleEndian(output, 2, 2)
            writeLittleEndian(output, 16, 2)
            output.writeBytes("data")
            writeLittleEndian(output, dataLength, 4)
        }
    }

    private fun writeLittleEndian(output: RandomAccessFile, value: Long, bytes: Int) {
        repeat(bytes) { offset ->
            output.write((value shr (offset * 8) and 0xff).toInt())
        }
    }
}
