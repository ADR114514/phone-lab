package com.mobilelab.vibrodiag.data

import com.mobilelab.vibrodiag.model.Conditions
import java.util.Random

/**
 * Blind test plan: 3 trials per condition, shuffled once when the test starts.
 * The true condition is stored here but the UI only reveals it after the model
 * has produced its prediction.
 */
class BlindSession private constructor(val trials: List<String>) {

    val total: Int get() = trials.size

    fun conditionForTrial(index: Int): String? = trials.getOrNull(index)

    companion object {
        const val TRIALS_PER_CONDITION = 3

        fun create(random: Random = Random(System.nanoTime())): BlindSession {
            val trials = ArrayList<String>(Conditions.all.size * TRIALS_PER_CONDITION)
            for (condition in Conditions.all) {
                repeat(TRIALS_PER_CONDITION) { trials.add(condition.id) }
            }
            trials.shuffle(random)
            return BlindSession(trials)
        }
    }
}
