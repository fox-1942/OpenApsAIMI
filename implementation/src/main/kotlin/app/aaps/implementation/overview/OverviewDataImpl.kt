package app.aaps.implementation.overview

import android.content.Context
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.iob.CobInfo
import app.aaps.core.interfaces.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.iob.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.DefaultValueHelper
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.T
import app.aaps.core.main.R
import app.aaps.core.main.extensions.convertedToPercent
import app.aaps.core.main.extensions.isInProgress
import app.aaps.core.main.extensions.toStringFull
import app.aaps.core.main.extensions.toStringShort
import app.aaps.core.main.extensions.valueToUnits
import app.aaps.core.main.graph.OverviewData
import app.aaps.core.main.graph.data.DataPointWithLabelInterface
import app.aaps.core.main.graph.data.DeviationDataPoint
import app.aaps.core.main.graph.data.FixedLineGraphSeries
import app.aaps.core.main.graph.data.PointsWithLabelGraphSeries
import app.aaps.core.main.graph.data.Scale
import app.aaps.core.main.graph.data.ScaledDataPoint
import app.aaps.core.main.iob.round
import app.aaps.database.ValueWrapper
import app.aaps.database.entities.GlucoseValue
import app.aaps.database.entities.TemporaryTarget
import app.aaps.database.impl.AppRepository
import com.jjoe64.graphview.series.BarGraphSeries
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverviewDataImpl @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    private val dateUtil: DateUtil,
    private val sp: SP,
    private val activePlugin: ActivePlugin,
    private val defaultValueHelper: DefaultValueHelper,
    private val profileFunction: ProfileFunction,
    private val repository: AppRepository,
    private val decimalFormatter: DecimalFormatter
) : OverviewData {

    override var rangeToDisplay = 6 // for graph
    override var toTime: Long = 0
    override var fromTime: Long = 0
    override var endTime: Long = 0

    override fun reset() {
        pumpStatus = ""
        calcProgressPct = 100
        bgReadingsArray = ArrayList()
        maxBgValue = Double.MIN_VALUE
        bucketedGraphSeries = PointsWithLabelGraphSeries()
        bgReadingGraphSeries = PointsWithLabelGraphSeries()
        predictionsGraphSeries = PointsWithLabelGraphSeries()
        baseBasalGraphSeries = LineGraphSeries()
        tempBasalGraphSeries = LineGraphSeries()
        basalLineGraphSeries = LineGraphSeries()
        absoluteBasalGraphSeries = LineGraphSeries()
        temporaryTargetSeries = LineGraphSeries()
        maxIAValue = 0.0
        activitySeries = FixedLineGraphSeries()
        activityPredictionSeries = FixedLineGraphSeries()
        maxIobValueFound = Double.MIN_VALUE
        iobSeries = FixedLineGraphSeries()
        absIobSeries = FixedLineGraphSeries()
        iobPredictions1Series = PointsWithLabelGraphSeries()
        //iobPredictions2Series = PointsWithLabelGraphSeries()
        maxBGIValue = Double.MIN_VALUE
        minusBgiSeries = FixedLineGraphSeries()
        minusBgiHistSeries = FixedLineGraphSeries()
        maxCobValueFound = Double.MIN_VALUE
        cobSeries = FixedLineGraphSeries()
        cobMinFailOverSeries = PointsWithLabelGraphSeries()
        maxDevValueFound = Double.MIN_VALUE
        deviationsSeries = BarGraphSeries()
        maxRatioValueFound = 5.0                    //even if sens data equals 0 for all the period, minimum scale is between 95% and 105%
        minRatioValueFound = -maxRatioValueFound
        ratioSeries = LineGraphSeries()
        maxFromMaxValueFound = Double.MIN_VALUE
        maxFromMinValueFound = Double.MIN_VALUE
        dsMaxSeries = LineGraphSeries()
        dsMinSeries = LineGraphSeries()
        maxTreatmentsValue = 0.0
        treatmentsSeries = PointsWithLabelGraphSeries()
        maxEpsValue = 0.0
        epsSeries = PointsWithLabelGraphSeries()
        maxTherapyEventValue = 0.0
        therapyEventSeries = PointsWithLabelGraphSeries()
        heartRateGraphSeries = PointsWithLabelGraphSeries()
        stepsCountGraphSeries = PointsWithLabelGraphSeries()
    }

    override fun initRange() {
        rangeToDisplay = sp.getInt(app.aaps.core.utils.R.string.key_rangetodisplay, 6)

        val calendar = Calendar.getInstance().also {
            it.timeInMillis = System.currentTimeMillis()
            it[Calendar.MILLISECOND] = 0
            it[Calendar.SECOND] = 0
            it[Calendar.MINUTE] = 0
            it.add(Calendar.HOUR, 1)
        }

        toTime = calendar.timeInMillis + 100000 // little bit more to avoid wrong rounding - GraphView specific
        fromTime = toTime - T.hours(rangeToDisplay.toLong()).msecs()
        endTime = toTime
    }

    /*
     * PUMP STATUS
     */

    override var pumpStatus: String = ""

    /*
     * CALC PROGRESS
     */

    override var calcProgressPct: Int = 100

    /*
     * BG
     */

    override fun lastBg(autosensDataStore: AutosensDataStore): InMemoryGlucoseValue? =
        autosensDataStore.bucketedData?.firstOrNull()
            ?: repository.getLastGlucoseValueWrapped().blockingGet().let { gvWrapped ->
                if (gvWrapped is ValueWrapper.Existing) InMemoryGlucoseValue(gvWrapped.value)
                else null
            }

    override fun isLow(autosensDataStore: AutosensDataStore): Boolean =
        lastBg(autosensDataStore)?.let { lastBg ->
            lastBg.valueToUnits(profileFunction.getUnits()) < defaultValueHelper.determineLowLine()
        } ?: false

    override fun isHigh(autosensDataStore: AutosensDataStore): Boolean =
        lastBg(autosensDataStore)?.let { lastBg ->
            lastBg.valueToUnits(profileFunction.getUnits()) > defaultValueHelper.determineHighLine()
        } ?: false

    @ColorInt
    override fun lastBgColor(context: Context?, autosensDataStore: AutosensDataStore): Int =
        when {
            isLow(autosensDataStore)  -> rh.gac(context, app.aaps.core.ui.R.attr.bgLow)
            isHigh(autosensDataStore) -> rh.gac(context, app.aaps.core.ui.R.attr.highColor)
            else                      -> rh.gac(context, app.aaps.core.ui.R.attr.bgInRange)
        }

    override fun lastBgDescription(autosensDataStore: AutosensDataStore): String =
        when {
            isLow(autosensDataStore)  -> rh.gs(app.aaps.core.ui.R.string.a11y_low)
            isHigh(autosensDataStore) -> rh.gs(app.aaps.core.ui.R.string.a11y_high)
            else                      -> rh.gs(app.aaps.core.ui.R.string.a11y_inrange)
        }

    override fun isActualBg(autosensDataStore: AutosensDataStore): Boolean =
        lastBg(autosensDataStore)?.let { lastBg ->
            lastBg.timestamp > dateUtil.now() - T.mins(9).msecs()
        } ?: false

    /*
     * TEMPORARY BASAL
     */

    override fun temporaryBasalText(iobCobCalculator: IobCobCalculator): String =
        profileFunction.getProfile()?.let { profile ->
            var temporaryBasal = iobCobCalculator.getTempBasalIncludingConvertedExtended(dateUtil.now())
            if (temporaryBasal?.isInProgress == false) temporaryBasal = null
            temporaryBasal?.let { "T:" + it.toStringShort(decimalFormatter) }
                ?: rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, profile.getBasal())
        } ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)

    override fun temporaryBasalDialogText(iobCobCalculator: IobCobCalculator): String =
        profileFunction.getProfile()?.let { profile ->
            iobCobCalculator.getTempBasalIncludingConvertedExtended(dateUtil.now())?.let { temporaryBasal ->
                "${rh.gs(app.aaps.core.ui.R.string.base_basal_rate_label)}: ${rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, profile.getBasal())}" +
                    "\n" + rh.gs(app.aaps.core.ui.R.string.tempbasal_label) + ": " + temporaryBasal.toStringFull(profile, dateUtil, decimalFormatter)
            }
                ?: "${rh.gs(app.aaps.core.ui.R.string.base_basal_rate_label)}: ${rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, profile.getBasal())}"
        } ?: rh.gs(app.aaps.core.ui.R.string.value_unavailable_short)

    @DrawableRes override fun temporaryBasalIcon(iobCobCalculator: IobCobCalculator): Int =
        profileFunction.getProfile()?.let { profile ->
            iobCobCalculator.getTempBasalIncludingConvertedExtended(dateUtil.now())?.let { temporaryBasal ->
                val percentRate = temporaryBasal.convertedToPercent(dateUtil.now(), profile)
                when {
                    percentRate > 100 -> R.drawable.ic_cp_basal_tbr_high
                    percentRate < 100 -> R.drawable.ic_cp_basal_tbr_low
                    else              -> R.drawable.ic_cp_basal_no_tbr
                }
            }
        } ?: R.drawable.ic_cp_basal_no_tbr

    @AttrRes override fun temporaryBasalColor(context: Context?, iobCobCalculator: IobCobCalculator): Int = iobCobCalculator.getTempBasalIncludingConvertedExtended(dateUtil.now())?.let {
        rh.gac(
            context, app.aaps.core.ui.R
                .attr.basal
        )
    }
        ?: rh.gac(context, app.aaps.core.ui.R.attr.defaultTextColor)

    /*
     * EXTENDED BOLUS
    */

    override fun extendedBolusText(iobCobCalculator: IobCobCalculator): String =
        iobCobCalculator.getExtendedBolus(dateUtil.now())?.let { extendedBolus ->
            if (!extendedBolus.isInProgress(dateUtil)) ""
            else if (!activePlugin.activePump.isFakingTempsByExtendedBoluses) rh.gs(app.aaps.core.ui.R.string.pump_base_basal_rate, extendedBolus.rate)
            else ""
        } ?: ""

    override fun extendedBolusDialogText(iobCobCalculator: IobCobCalculator): String =
        iobCobCalculator.getExtendedBolus(dateUtil.now())?.toStringFull(dateUtil, decimalFormatter) ?: ""

    /*
     * IOB, COB
     */

    override fun bolusIob(iobCobCalculator: IobCobCalculator): IobTotal = iobCobCalculator.calculateIobFromBolus().round()
    override fun basalIob(iobCobCalculator: IobCobCalculator): IobTotal = iobCobCalculator.calculateIobFromTempBasalsIncludingConvertedExtended().round()
    override fun cobInfo(iobCobCalculator: IobCobCalculator): CobInfo = iobCobCalculator.getCobInfo("Overview COB")

    override val lastCarbsTime: Long
        get() = repository.getLastCarbsRecordWrapped().blockingGet().let { lastCarbs ->
            if (lastCarbs is ValueWrapper.Existing) lastCarbs.value.timestamp else 0L
        }

    override fun iobText(iobCobCalculator: IobCobCalculator): String =
        rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolusIob(iobCobCalculator).iob + basalIob(iobCobCalculator).basaliob)

    override fun iobDialogText(iobCobCalculator: IobCobCalculator): String =
        rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolusIob(iobCobCalculator).iob + basalIob(iobCobCalculator).basaliob) + "\n" +
            rh.gs(app.aaps.core.ui.R.string.bolus) + ": " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolusIob(iobCobCalculator).iob) + "\n" +
            rh.gs(app.aaps.core.ui.R.string.basal) + ": " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, basalIob(iobCobCalculator).basaliob)

    /*
     * TEMP TARGET
     */

    override val temporaryTarget: TemporaryTarget?
        get() =
            repository.getTemporaryTargetActiveAt(dateUtil.now()).blockingGet().let { tempTarget ->
                if (tempTarget is ValueWrapper.Existing) tempTarget.value
                else null
            }

    /*
     * SENSITIVITY
     */

    override fun lastAutosensData(iobCobCalculator: IobCobCalculator): AutosensData? = iobCobCalculator.ads.getLastAutosensData("Overview", aapsLogger, dateUtil)

    /*
     * Graphs
     */

    override var bgReadingsArray: List<GlucoseValue> = ArrayList()
    override var maxBgValue = Double.MIN_VALUE
    override var bucketedGraphSeries: PointsWithLabelGraphSeries<DataPointWithLabelInterface> =
        PointsWithLabelGraphSeries()
    override var bgReadingGraphSeries: PointsWithLabelGraphSeries<DataPointWithLabelInterface> =
        PointsWithLabelGraphSeries()
    override var predictionsGraphSeries: PointsWithLabelGraphSeries<DataPointWithLabelInterface> =
        PointsWithLabelGraphSeries()

    override val basalScale = Scale()
    override var baseBasalGraphSeries: LineGraphSeries<ScaledDataPoint> = LineGraphSeries()
    override var tempBasalGraphSeries: LineGraphSeries<ScaledDataPoint> = LineGraphSeries()
    override var basalLineGraphSeries: LineGraphSeries<ScaledDataPoint> = LineGraphSeries()
    override var absoluteBasalGraphSeries: LineGraphSeries<ScaledDataPoint> = LineGraphSeries()

    override var temporaryTargetSeries: LineGraphSeries<DataPoint> = LineGraphSeries()

    override var maxIAValue = 0.0
    override val actScale = Scale()
    override var activitySeries: FixedLineGraphSeries<ScaledDataPoint> = FixedLineGraphSeries()
    override var activityPredictionSeries: FixedLineGraphSeries<ScaledDataPoint> = FixedLineGraphSeries()

    override var maxEpsValue = 0.0
    override val epsScale = Scale()
    override var epsSeries: PointsWithLabelGraphSeries<DataPointWithLabelInterface> =
        PointsWithLabelGraphSeries()
    override var maxTreatmentsValue = 0.0
    override var treatmentsSeries: PointsWithLabelGraphSeries<DataPointWithLabelInterface> =
        PointsWithLabelGraphSeries()
    override var maxTherapyEventValue = 0.0
    override var therapyEventSeries: PointsWithLabelGraphSeries<DataPointWithLabelInterface> =
        PointsWithLabelGraphSeries()

    override var maxIobValueFound = Double.MIN_VALUE
    override val iobScale = Scale()
    override var iobSeries: FixedLineGraphSeries<ScaledDataPoint> = FixedLineGraphSeries()
    override var absIobSeries: FixedLineGraphSeries<ScaledDataPoint> = FixedLineGraphSeries()
    override var iobPredictions1Series: PointsWithLabelGraphSeries<DataPointWithLabelInterface> = PointsWithLabelGraphSeries()

    override var maxBGIValue = Double.MIN_VALUE
    override val bgiScale = Scale()
    override var minusBgiSeries: FixedLineGraphSeries<ScaledDataPoint> = FixedLineGraphSeries()
    override var minusBgiHistSeries: FixedLineGraphSeries<ScaledDataPoint> = FixedLineGraphSeries()

    override var maxCobValueFound = Double.MIN_VALUE
    override val cobScale = Scale()
    override var cobSeries: FixedLineGraphSeries<ScaledDataPoint> = FixedLineGraphSeries()
    override var cobMinFailOverSeries: PointsWithLabelGraphSeries<DataPointWithLabelInterface> =
        PointsWithLabelGraphSeries()

    override var maxDevValueFound = Double.MIN_VALUE
    override val devScale = Scale()
    override var deviationsSeries: BarGraphSeries<DeviationDataPoint> = BarGraphSeries()

    override var maxRatioValueFound = 5.0                    //even if sens data equals 0 for all the period, minimum scale is between 95% and 105%
    override var minRatioValueFound = -maxRatioValueFound
    override val ratioScale = Scale()
    override var ratioSeries: LineGraphSeries<ScaledDataPoint> = LineGraphSeries()

    override var maxFromMaxValueFound = Double.MIN_VALUE
    override var maxFromMinValueFound = Double.MIN_VALUE
    override val dsMaxScale = Scale()
    override val dsMinScale = Scale()
    override var dsMaxSeries: LineGraphSeries<ScaledDataPoint> = LineGraphSeries()
    override var dsMinSeries: LineGraphSeries<ScaledDataPoint> = LineGraphSeries()
    override var heartRateScale = Scale()
    override var heartRateGraphSeries: PointsWithLabelGraphSeries<DataPointWithLabelInterface> = PointsWithLabelGraphSeries()
    override var stepsForScale = Scale()
    override var stepsCountGraphSeries: PointsWithLabelGraphSeries<DataPointWithLabelInterface> = PointsWithLabelGraphSeries()
}