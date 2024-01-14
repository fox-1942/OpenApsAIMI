package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.database.entities.TherapyEvent
import app.aaps.database.impl.AppRepository
import java.util.concurrent.TimeUnit

enum class ModeType(){
    SPORT,
    SLEEP,
    LOWCARB,
    SNACK,
    HIGHCARB,
    MEAL
}

class Mode (var intervalSmb : Int,
            var dynIsfFactor : Int,
            val modeType : ModeType,
            private val appRepository: AppRepository
) {

    fun getTimeElapsedSinceLastEvent(keyword: String): Long {
        val fromTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(60)
        val events = appRepository.getTherapyEventDataFromTime(fromTime, TherapyEvent.Type.NOTE, true).blockingGet()

        val lastEvent = events.filter { it.note?.contains(keyword, ignoreCase = true) == true }
            .maxByOrNull { it.timestamp }
        lastEvent?.let {
            return (System.currentTimeMillis() - it.timestamp) / 60000
        }
        return -1
    }
}