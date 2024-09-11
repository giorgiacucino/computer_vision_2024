package com.example.appsl

import android.content.ContentValues.TAG
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class Classifier(private val context: Context, modelPath: String) {
    private var interpreter: Interpreter
    private var labels: List<String>

    init {
        val options = Interpreter.Options()
        interpreter = Interpreter(FileUtil.loadMappedFile(context, modelPath), options)

        labels = LABELS
    }

    fun classify(tensorImage: TensorImage): String {
        val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, labels.size), DataType.FLOAT32)

        interpreter.run(tensorImage.buffer, outputBuffer.buffer)

        val probabilities = outputBuffer.floatArray
        val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: -1
        Log.d(TAG, "index: $maxIndex")
        return if (maxIndex != -1) labels[maxIndex] else "Unknown"
    }

    fun close() {
        interpreter.close()
    }

    companion object {
        private val LABELS = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "del", "nothing","space")
    }
}