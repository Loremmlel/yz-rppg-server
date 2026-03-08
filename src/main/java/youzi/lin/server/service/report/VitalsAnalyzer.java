package youzi.lin.server.service.report;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.springframework.stereotype.Component;
import youzi.lin.server.dto.VitalsAnalysisSummary;
import youzi.lin.server.dto.VitalsTrendDto;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 指标分析策略接口。
 */
public interface VitalsAnalyzer {

    String metricKey();

    VitalsAnalysisSummary.MetricAnalysis analyze(List<VitalsTrendDto> trends);
}

abstract class AbstractVitalsAnalyzer implements VitalsAnalyzer {

    private final String metricKey;
    private final String metricName;
    private final String unit;
    private final Double normalLow;
    private final Double normalHigh;
    private final double slopeThreshold;
    private final double fluctuationThreshold;
    private final Function<VitalsTrendDto, Double> extractor;

    protected AbstractVitalsAnalyzer(String metricKey,
                                     String metricName,
                                     String unit,
                                     Double normalLow,
                                     Double normalHigh,
                                     double slopeThreshold,
                                     double fluctuationThreshold,
                                     Function<VitalsTrendDto, Double> extractor) {
        this.metricKey = metricKey;
        this.metricName = metricName;
        this.unit = unit;
        this.normalLow = normalLow;
        this.normalHigh = normalHigh;
        this.slopeThreshold = slopeThreshold;
        this.fluctuationThreshold = fluctuationThreshold;
        this.extractor = extractor;
    }

    @Override
    public String metricKey() {
        return metricKey;
    }

    @Override
    public VitalsAnalysisSummary.MetricAnalysis analyze(List<VitalsTrendDto> trends) {
        List<Double> values = new ArrayList<>();
        int xIndex = 0;
        var regression = new SimpleRegression(true);
        for (VitalsTrendDto trend : trends) {
            Double value = extractor.apply(trend);
            if (value == null) {
                continue;
            }
            values.add(value);
            regression.addData(xIndex++, value);
        }

        var analysis = new VitalsAnalysisSummary.MetricAnalysis();
        analysis.setMetricKey(metricKey);
        analysis.setMetricName(metricName);
        analysis.setUnit(unit);
        analysis.setNormalRange(buildRangeText());
        analysis.setSampleCount(values.size());

        if (values.isEmpty()) {
            analysis.setStatus(VitalsAnalysisSummary.MetricStatus.UNKNOWN);
            analysis.setTrend(VitalsAnalysisSummary.TrendDirection.UNKNOWN);
            analysis.setStability(VitalsAnalysisSummary.StabilityStatus.UNKNOWN);
            analysis.setSlope(0.0);
            analysis.setStandardDeviation(0.0);
            return analysis;
        }

        Double latest = values.get(values.size() - 1);
        analysis.setLatestValue(latest);
        analysis.setStatus(judgeStatus(latest));

        double slope = values.size() > 1 ? regression.getSlope() : 0.0;
        analysis.setSlope(slope);
        analysis.setTrend(judgeTrend(slope));

        var ds = new DescriptiveStatistics();
        values.forEach(ds::addValue);
        double std = values.size() > 1 ? ds.getStandardDeviation() : 0.0;
        analysis.setStandardDeviation(std);
        analysis.setStability(judgeStability(std));
        return analysis;
    }

    private String buildRangeText() {
        if (normalLow == null && normalHigh == null) {
            return "N/A";
        }
        if (normalLow == null) {
            return "<= " + normalHigh;
        }
        if (normalHigh == null) {
            return ">= " + normalLow;
        }
        return normalLow + " ~ " + normalHigh;
    }

    // 医学阈值判定：使用最新时间桶值作为当前状态。
    private VitalsAnalysisSummary.MetricStatus judgeStatus(Double latest) {
        if (latest == null) {
            return VitalsAnalysisSummary.MetricStatus.UNKNOWN;
        }
        if (normalLow != null && latest < normalLow) {
            return VitalsAnalysisSummary.MetricStatus.LOW;
        }
        if (normalHigh != null && latest > normalHigh) {
            return VitalsAnalysisSummary.MetricStatus.HIGH;
        }
        return VitalsAnalysisSummary.MetricStatus.NORMAL;
    }

    // 趋势判定：基于线性回归斜率，绝对值小于阈值视为稳定。
    private VitalsAnalysisSummary.TrendDirection judgeTrend(double slope) {
        if (slope > slopeThreshold) {
            return VitalsAnalysisSummary.TrendDirection.RISING;
        }
        if (slope < -slopeThreshold) {
            return VitalsAnalysisSummary.TrendDirection.FALLING;
        }
        return VitalsAnalysisSummary.TrendDirection.STABLE;
    }

    // 稳定性判定：标准差超过阈值认为波动较大。
    private VitalsAnalysisSummary.StabilityStatus judgeStability(double std) {
        return std > fluctuationThreshold
                ? VitalsAnalysisSummary.StabilityStatus.FLUCTUATING
                : VitalsAnalysisSummary.StabilityStatus.STEADY;
    }
}

/** 心率分析器，正常范围 60~100 bpm（静息成人标准）。 */
@Component
class HrAnalyzer extends AbstractVitalsAnalyzer {
    HrAnalyzer() {
        super("hr", "心率", "bpm", 60.0, 100.0, 0.2, 8.0,
                trend -> trend.getBasicVitals() != null ? trend.getBasicVitals().getHrAvg() : null);
    }
}

/** SDNN 分析器，反映总体 HRV 水平，正常范围 30~100 ms。 */
@Component
class HrvSdnnAnalyzer extends AbstractVitalsAnalyzer {
    HrvSdnnAnalyzer() {
        super("hrv_sdnn", "HRV-SDNN", "ms", 30.0, 100.0, 0.2, 12.0,
                trend -> trend.getHrvTimeDomain() != null ? trend.getHrvTimeDomain().getSdnnMedian() : null);
    }
}

/** RMSSD 分析器，反映副交感神经活性，正常范围 20~80 ms。 */
@Component
class HrvRmssdAnalyzer extends AbstractVitalsAnalyzer {
    HrvRmssdAnalyzer() {
        super("hrv_rmssd", "HRV-RMSSD", "ms", 20.0, 80.0, 0.2, 10.0,
                trend -> trend.getHrvTimeDomain() != null ? trend.getHrvTimeDomain().getRmssdMedian() : null);
    }
}

/** SDSD 分析器，与 RMSSD 互为补充，正常范围 15~80 ms。 */
@Component
class HrvSdsdAnalyzer extends AbstractVitalsAnalyzer {
    HrvSdsdAnalyzer() {
        super("hrv_sdsd", "HRV-SDSD", "ms", 15.0, 80.0, 0.2, 10.0,
                trend -> trend.getHrvTimeDomain() != null ? trend.getHrvTimeDomain().getSdsdMedian() : null);
    }
}

/** pNN50 分析器，相邻 RR 间期差值 &gt;50 ms 的比例，正常范围 3%~25%。 */
@Component
class HrvPnn50Analyzer extends AbstractVitalsAnalyzer {
    HrvPnn50Analyzer() {
        super("hrv_pnn50", "HRV-pNN50", "%", 3.0, 25.0, 0.1, 8.0,
                trend -> trend.getHrvTimeDomain() != null ? trend.getHrvTimeDomain().getPnn50Median() : null);
    }
}

/** pNN20 分析器，相邻 RR 间期差值 &gt;20 ms 的比例，正常范围 10%~45%。 */
@Component
class HrvPnn20Analyzer extends AbstractVitalsAnalyzer {
    HrvPnn20Analyzer() {
        super("hrv_pnn20", "HRV-pNN20", "%", 10.0, 45.0, 0.1, 10.0,
                trend -> trend.getHrvTimeDomain() != null ? trend.getHrvTimeDomain().getPnn20Median() : null);
    }
}

/** 信号质量指数分析器，低于 0.5 时 HRV 数据不可信。 */
@Component
class SqiAnalyzer extends AbstractVitalsAnalyzer {
    SqiAnalyzer() {
        super("sqi", "信号质量指数", "", 0.5, 1.0, 0.01, 0.1,
                trend -> trend.getBasicVitals() != null ? trend.getBasicVitals().getSqiAvg() : null);
    }
}

/** 呼吸率分析器，正常范围 0.18~0.33 Hz（约 11~20 次/分）。 */
@Component
class BrAnalyzer extends AbstractVitalsAnalyzer {
    BrAnalyzer() {
        super("br", "呼吸率", "Hz", 0.18, 0.33, 0.002, 0.05,
                trend -> trend.getBasicVitals() != null ? trend.getBasicVitals().getBrAvg() : null);
    }
}

/** LF/HF 比值分析器，反映自主神经平衡，正常范围 0.5~2.0。 */
@Component
class LfHfAnalyzer extends AbstractVitalsAnalyzer {
    LfHfAnalyzer() {
        super("lf_hf", "LF/HF 比值", "", 0.5, 2.0, 0.05, 0.5,
                trend -> trend.getHrvFreqDomain() != null ? trend.getHrvFreqDomain().getLfHfRatio() : null);
    }
}

/** HF 功率分析器，反映副交感神经活性，正常范围 200~1500 ms²。 */
@Component
class HfAnalyzer extends AbstractVitalsAnalyzer {
    HfAnalyzer() {
        super("hf", "HF", "ms²", 200.0, 1500.0, 3.0, 120.0,
                trend -> trend.getHrvFreqDomain() != null ? trend.getHrvFreqDomain().getHfAvg() : null);
    }
}

/** LF 功率分析器，反映交感与副交感混合活性，正常范围 300~1700 ms²。 */
@Component
class LfAnalyzer extends AbstractVitalsAnalyzer {
    LfAnalyzer() {
        super("lf", "LF", "ms²", 300.0, 1700.0, 3.0, 130.0,
                trend -> trend.getHrvFreqDomain() != null ? trend.getHrvFreqDomain().getLfAvg() : null);
    }
}

/** VLF 功率分析器，正常范围 100~1200 ms²。 */
@Component
class VlfAnalyzer extends AbstractVitalsAnalyzer {
    VlfAnalyzer() {
        super("vlf", "VLF", "ms²", 100.0, 1200.0, 2.0, 100.0,
                trend -> trend.getHrvFreqDomain() != null ? trend.getHrvFreqDomain().getVlfAvg() : null);
    }
}

/** 总功率分析器，反映整体 HRV 能量水平，正常范围 500~3500 ms²。 */
@Component
class TpAnalyzer extends AbstractVitalsAnalyzer {
    TpAnalyzer() {
        super("tp", "总功率", "ms²", 500.0, 3500.0, 5.0, 220.0,
                trend -> trend.getHrvFreqDomain() != null ? trend.getHrvFreqDomain().getTpAvg() : null);
    }
}

