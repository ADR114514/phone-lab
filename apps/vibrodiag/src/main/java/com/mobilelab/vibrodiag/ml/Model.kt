package com.mobilelab.vibrodiag.ml

/** One labelled feature vector used for training/evaluation. */
data class LabeledFeatures(val label: String, val features: DoubleArray)

/** Classification output with the weighted neighbour confidence required by the spec. */
data class Prediction(
    val label: String,
    val confidence: Double,
    val neighborLabels: List<String>,
    val distances: List<Double>,
)

/** Model parameters that are written into evaluation JSON so runs are reproducible. */
object ModelSpec {
    const val K = 3
    const val DESCRIPTOR = "knn(k=3, zscore)"
    const val STANDARDIZATION = "z-score (mean/std from training fold, std=1 when 0)"
}
