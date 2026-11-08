package com.neirofm.voice

/**
 * State contract for the optional bundled/downloaded Sherpa-ONNX neural model.
 * The UI must never report neural synthesis as ready until a model runtime is loaded.
 */
data class NeuralVoiceStatus(
    val engine: String = "Sherpa-ONNX",
    val modelName: String = "Русская Piper voice",
    val isInstalled: Boolean = false,
    val isReady: Boolean = false,
    val message: String = "Нейросетевая модель ещё не установлена"
)
