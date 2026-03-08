package youzi.lin.server.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * 多指标规则分析汇总结果，包含每个生命体征指标的独立分析报告。
 */
public class VitalsAnalysisSummary {

    private final List<MetricAnalysis> metrics = new ArrayList<>();

    public List<MetricAnalysis> getMetrics() {
        return metrics;
    }

    /**
     * 添加单个指标分析结果；{@code null} 值被忽略，避免空指针污染列表。
     */
    public void addMetric(MetricAnalysis metric) {
        if (metric != null) {
            metrics.add(metric);
        }
    }

    /**
     * 单个指标的完整分析结果，包含最新值、正常范围、状态判定、趋势和稳定性。
     */
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

    /**
     * 指标状态：与正常范围对比的结果。
     */
    public enum MetricStatus {
        /** 在正常范围内 */
        NORMAL,
        /** 高于正常上限 */
        HIGH,
        /** 低于正常下限 */
        LOW,
        /** 数据不足，无法判断 */
        UNKNOWN
    }

    /**
     * 趋势方向：基于线性回归斜率判定。
     */
    public enum TrendDirection {
        /** 上升趋势 */
        RISING,
        /** 下降趋势 */
        FALLING,
        /** 相对平稳 */
        STABLE,
        /** 数据不足，无法判断 */
        UNKNOWN
    }

    /**
     * 稳定性状态：基于标准差判定。
     */
    public enum StabilityStatus {
        /** 波动较大（标准差超过阈值） */
        FLUCTUATING,
        /** 相对稳定 */
        STEADY,
        /** 数据不足，无法判断 */
        UNKNOWN
    }
}

