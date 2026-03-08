package youzi.lin.server.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 多指标规则分析汇总结果。
 */
public class VitalsAnalysisSummary {

    private final List<MetricAnalysis> metrics = new ArrayList<>();

    public List<MetricAnalysis> getMetrics() {
        return metrics;
    }

    public void addMetric(MetricAnalysis metric) {
        if (metric != null) {
            metrics.add(metric);
        }
    }

    public static class MetricAnalysis {
        private String metricKey;
        private String metricName;
        private String unit;
        private Double latestValue;
        private String normalRange;
        private MetricStatus status;
        private TrendDirection trend;
        private StabilityStatus stability;
        private Double slope;
        private Double standardDeviation;
        private int sampleCount;

        public String getMetricKey() {
            return metricKey;
        }

        public void setMetricKey(String metricKey) {
            this.metricKey = metricKey;
        }

        public String getMetricName() {
            return metricName;
        }

        public void setMetricName(String metricName) {
            this.metricName = metricName;
        }

        public String getUnit() {
            return unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }

        public Double getLatestValue() {
            return latestValue;
        }

        public void setLatestValue(Double latestValue) {
            this.latestValue = latestValue;
        }

        public String getNormalRange() {
            return normalRange;
        }

        public void setNormalRange(String normalRange) {
            this.normalRange = normalRange;
        }

        public MetricStatus getStatus() {
            return status;
        }

        public void setStatus(MetricStatus status) {
            this.status = status;
        }

        public TrendDirection getTrend() {
            return trend;
        }

        public void setTrend(TrendDirection trend) {
            this.trend = trend;
        }

        public StabilityStatus getStability() {
            return stability;
        }

        public void setStability(StabilityStatus stability) {
            this.stability = stability;
        }

        public Double getSlope() {
            return slope;
        }

        public void setSlope(Double slope) {
            this.slope = slope;
        }

        public Double getStandardDeviation() {
            return standardDeviation;
        }

        public void setStandardDeviation(Double standardDeviation) {
            this.standardDeviation = standardDeviation;
        }

        public int getSampleCount() {
            return sampleCount;
        }

        public void setSampleCount(int sampleCount) {
            this.sampleCount = sampleCount;
        }
    }

    public enum MetricStatus {
        NORMAL,
        HIGH,
        LOW,
        UNKNOWN
    }

    public enum TrendDirection {
        RISING,
        FALLING,
        STABLE,
        UNKNOWN
    }

    public enum StabilityStatus {
        FLUCTUATING,
        STEADY,
        UNKNOWN
    }
}

