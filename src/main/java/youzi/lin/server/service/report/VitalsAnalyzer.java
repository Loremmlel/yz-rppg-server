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

@Component
class HrAnalyzer extends AbstractVitalsAnalyzer {
    HrAnalyzer() {
        super("hr", "心率", "bpm", 60.0, 100.0, 0.2, 8.0,
                trend -> trend.getBasicVitals() != null ? trend.getBasicVitals().getHrAvg() : null);
    }
}

@Component
class HrvSdnnAnalyzer extends AbstractVitalsAnalyzer {
    HrvSdnnAnalyzer() {
        super("hrv_sdnn", "HRV-SDNN", "ms", 30.0, 100.0, 0.2, 12.0,
                trend -> trend.getHrvTimeDomain() != null ? trend.getHrvTimeDomain().getSdnnMedian() : null);
    }
}

@Component
class HrvRmssdAnalyzer extends AbstractVitalsAnalyzer {
    HrvRmssdAnalyzer() {
        super("hrv_rmssd", "HRV-RMSSD", "ms", 20.0, 80.0, 0.2, 10.0,
                trend -> trend.getHrvTimeDomain() != null ? trend.getHrvTimeDomain().getRmssdMedian() : null);
    }
}

@Component
class HrvSdsdAnalyzer extends AbstractVitalsAnalyzer {
    HrvSdsdAnalyzer() {
        super("hrv_sdsd", "HRV-SDSD", "ms", 15.0, 80.0, 0.2, 10.0,
                trend -> trend.getHrvTimeDomain() != null ? trend.getHrvTimeDomain().getSdsdMedian() : null);
    }
}

@Component
class HrvPnn50Analyzer extends AbstractVitalsAnalyzer {
    HrvPnn50Analyzer() {
        super("hrv_pnn50", "HRV-pNN50", "%", 3.0, 25.0, 0.1, 8.0,
                trend -> trend.getHrvTimeDomain() != null ? trend.getHrvTimeDomain().getPnn50Median() : null);
    }
}

@Component
class HrvPnn20Analyzer extends AbstractVitalsAnalyzer {
    HrvPnn20Analyzer() {
        super("hrv_pnn20", "HRV-pNN20", "%", 10.0, 45.0, 0.1, 10.0,
                trend -> trend.getHrvTimeDomain() != null ? trend.getHrvTimeDomain().getPnn20Median() : null);
    }
}

@Component
class SqiAnalyzer extends AbstractVitalsAnalyzer {
    SqiAnalyzer() {
        super("sqi", "信号质量指数", "", 0.5, 1.0, 0.01, 0.1,
                trend -> trend.getBasicVitals() != null ? trend.getBasicVitals().getSqiAvg() : null);
    }
}

@Component
class BrAnalyzer extends AbstractVitalsAnalyzer {
    BrAnalyzer() {
        super("br", "呼吸率", "Hz", 0.18, 0.33, 0.002, 0.05,
                trend -> trend.getBasicVitals() != null ? trend.getBasicVitals().getBrAvg() : null);
    }
}

@Component
class LfHfAnalyzer extends AbstractVitalsAnalyzer {
    LfHfAnalyzer() {
        super("lf_hf", "LF/HF 比值", "", 0.5, 2.0, 0.05, 0.5,
                trend -> trend.getHrvFreqDomain() != null ? trend.getHrvFreqDomain().getLfHfRatio() : null);
    }
}

@Component
class HfAnalyzer extends AbstractVitalsAnalyzer {
    HfAnalyzer() {
        super("hf", "HF", "ms2", 200.0, 1500.0, 3.0, 120.0,
                trend -> trend.getHrvFreqDomain() != null ? trend.getHrvFreqDomain().getHfAvg() : null);
    }
}

@Component
class LfAnalyzer extends AbstractVitalsAnalyzer {
    LfAnalyzer() {
        super("lf", "LF", "ms2", 300.0, 1700.0, 3.0, 130.0,
                trend -> trend.getHrvFreqDomain() != null ? trend.getHrvFreqDomain().getLfAvg() : null);
    }
}

@Component
class VlfAnalyzer extends AbstractVitalsAnalyzer {
    VlfAnalyzer() {
        super("vlf", "VLF", "ms2", 100.0, 1200.0, 2.0, 100.0,
                trend -> trend.getHrvFreqDomain() != null ? trend.getHrvFreqDomain().getVlfAvg() : null);
    }
}

@Component
class TpAnalyzer extends AbstractVitalsAnalyzer {
    TpAnalyzer() {
        super("tp", "总功率", "ms2", 500.0, 3500.0, 5.0, 220.0,
                trend -> trend.getHrvFreqDomain() != null ? trend.getHrvFreqDomain().getTpAvg() : null);
    }
}

