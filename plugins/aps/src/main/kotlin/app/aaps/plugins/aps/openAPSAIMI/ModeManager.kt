package app.aaps.plugins.aps.openAPSAIMI

import android.annotation.SuppressLint
import app.aaps.database.entities.TherapyEvent
import app.aaps.database.impl.AppRepository
import io.reactivex.rxjava3.core.Single
import java.util.concurrent.TimeUnit

class ModeManager(private val appRepository: AppRepository) {

    private val keywords = listOf("sleep", "sport", "snack", "lowcarb", "highcarb", "meal", "fasting")

    var sleepTime = false
    var sportTime = false
    var snackTime = false
    var lowCarbTime = false
    var highCarbTime = false
    var mealTime = false
    var fastingTime = false

    @SuppressLint("CheckResult")
    fun updateStatesBasedOnTherapyEvents() {
        sleepTime = findActiveEvent("sleep").blockingGet()
        sportTime = findActiveEvent("sport").blockingGet()
        snackTime = findActiveEvent("snack").blockingGet()
        lowCarbTime = findActiveEvent("lowcarb").blockingGet()
        highCarbTime = findActiveEvent("highcarb").blockingGet()
        mealTime = findActiveEvent("meal").blockingGet()
        fastingTime = findActiveEvent("fasting").blockingGet()

        var foundEvent : Single<String> = findLatestTherapyNote()
        keywords.forEach {
            if (it != foundEvent.blockingGet()) {
                clearActiveEvent(it)
            }
        }
    }

    private fun findLatestTherapyNote(): Single<String> {
        val fromTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        return appRepository.getTherapyEventDataFromTime(fromTime, TherapyEvent.Type.NOTE, true)
            .map { events ->
                events.filter { individualEvent -> individualEvent.note?.isNotEmpty() ?: false }
                    .maxByOrNull { it.timestamp }?.note ?: ""
            }
    }

    private fun findActiveEvent(keyword: String): Single<Boolean> {
        val fromTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        return appRepository.getTherapyEventDataFromTime(fromTime, TherapyEvent.Type.NOTE, true)
            .map { events ->
                events.any { event ->
                    event.note?.contains(keyword, ignoreCase = true) == true &&
                        System.currentTimeMillis() <= (event.timestamp + event.duration)
                }
            }
    }

    private fun clearActiveEvent(noteKeyword: String) {
        appRepository.deleteLastEventMatchingKeyword(noteKeyword)
    }

    private fun resetAllStates() {
        sleepTime = false
        sportTime = false
        snackTime = false
        lowCarbTime = false
        highCarbTime = false
        mealTime = false
        fastingTime = false
    }

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

    private fun isEventActive(event: TherapyEvent, currentTime: Long): Boolean {
        val eventEndTime = event.timestamp + event.duration
        return currentTime <= eventEndTime
    }
}
