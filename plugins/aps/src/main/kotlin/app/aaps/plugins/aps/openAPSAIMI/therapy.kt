package app.aaps.plugins.aps.openAPSAIMI

import android.annotation.SuppressLint
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import io.reactivex.rxjava3.core.Single
import java.util.Calendar
import java.util.concurrent.TimeUnit
class Therapy (private val persistenceLayer: PersistenceLayer){

    enum class AimiModeType {
        SLEEP,
        SPORT,
        SNACK,
        LOWCARB,
        HIGHCARB,
        MEAL,
        FASTING,
        STOP,
        CALIBRATION
    }

    var actualMode : AimiModeType? = null

    @SuppressLint("CheckResult")
    fun updateStatesBasedOnTherapyEvents() {
        if(findActivestopEvents(System.currentTimeMillis()).blockingGet()){
           actualMode = null
        }
        else{
            when(findLatestTherapyNoteType()?.note){
                "sleep" -> actualMode=AimiModeType.SLEEP
                "sport" -> actualMode=AimiModeType.SPORT
                "snack" -> actualMode=AimiModeType.SNACK
                "lowcarb" -> actualMode=AimiModeType.LOWCARB
                "highcarb" -> actualMode=AimiModeType.HIGHCARB
                "meal" -> actualMode=AimiModeType.MEAL
                "fasting" -> actualMode=AimiModeType.FASTING
                "stop" -> actualMode=AimiModeType.STOP
                "calibration" -> actualMode=AimiModeType.CALIBRATION
            }
        }
    }

    fun findLatestTherapyNoteType(): TE? {
        val fromTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        val listOfTE = persistenceLayer.getTherapyEventDataFromTime(fromTime, TE.Type.NOTE, true)

        // Iterate over the result list using a for loop
        var minTimeDifference: Long
        var latestEvent: TE? = null
        val currentTime: Long = System.currentTimeMillis()

        for (event in listOfTE) {
            val eventEndTime = event.timestamp + event.duration - System.currentTimeMillis()
            if (eventEndTime in 0..currentTime) {
                latestEvent = event
            }
        }
        return latestEvent
    }

    private fun findActivestopEvents(timestamp: Long): Single<Boolean> {
        val fromTime = timestamp - TimeUnit.DAYS.toMillis(1) // les dernières 24 heures
        return persistenceLayer.getTherapyEventDataFromTime(fromTime, true)
            .map { events ->
                events.filter { it.type == TE.Type.NOTE }
                    .any { event ->
                        event.note?.contains("stop", ignoreCase = true) == true &&
                            System.currentTimeMillis() <= (event.timestamp + event.duration)
                    }
            }
    }

    fun getTimeElapsedSinceLastEvent(keyword: String): Long {
        val fromTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(60)
        val events = persistenceLayer.getTherapyEventDataFromTime(fromTime, TE.Type.NOTE, true)

        val lastEvent = events.filter { it.note?.contains(keyword, ignoreCase = true) == true }
            .maxByOrNull { it.timestamp }
        lastEvent?.let {
            // Calculer et retourner le temps écoulé en minutes depuis l'événement
            return (System.currentTimeMillis() - it.timestamp) / 60000  // Convertir en minutes
        }
        return -1  // Retourner -1 si aucun événement n'a été trouvé
    }
}