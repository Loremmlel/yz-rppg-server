package youzi.lin.server.service.report;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import youzi.lin.server.dto.VitalsAnalysisSummary;
import youzi.lin.server.dto.VitalsTrendDto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 构建发送给 LLM 的文本 Prompt（Builder 模式）。
 */
@Component
public class PromptBuilder {

    private static final String TEMPLATE_PATH = "classpath:prompts/report-prompt.txt";

    private final String template;

    public PromptBuilder(ResourceLoader resourceLoader) {
        this.template = loadTemplate(resourceLoader);
    }

    public String build(List<VitalsTrendDto> trends, VitalsAnalysisSummary summary) {
        String dataSummary = buildDataSummary(trends);
        String analysisSummary = buildAnalysisSummary(summary);
        return new PromptTextBuilder(template)
                .role("你是一名医疗助手，输出内容严谨、客观，不给出确定性诊断结论。")
                .dataSummary(dataSummary)
                .analysisSummary(analysisSummary)
                .outputRequirement("请输出 Markdown 报告，必须包含：\\n1) 摘要\\n2) 详细分析\\n3) 护理/观察建议。")
                .build();
    }

    private String buildDataSummary(List<VitalsTrendDto> trends) {
        if (trends == null || trends.isEmpty()) {
            return "无可用趋势数据。";
        }
        var sb = new StringBuilder();
        sb.append("数据点数量=").append(trends.size()).append("\\n");
        int start = Math.max(0, trends.size() - 10);
        for (int i = start; i < trends.size(); i++) {
            VitalsTrendDto t = trends.get(i);
            sb.append("- time=").append(t.getBucketTime())
                    .append(", hr=").append(valueOf(t.getBasicVitals() != null ? t.getBasicVitals().getHrAvg() : null))
                    .append(", br=").append(valueOf(t.getBasicVitals() != null ? t.getBasicVitals().getBrAvg() : null))
                    .append(", sqi=").append(valueOf(t.getBasicVitals() != null ? t.getBasicVitals().getSqiAvg() : null))
                    .append(", sdnn=").append(valueOf(t.getHrvTimeDomain() != null ? t.getHrvTimeDomain().getSdnnMedian() : null))
                    .append(", rmssd=").append(valueOf(t.getHrvTimeDomain() != null ? t.getHrvTimeDomain().getRmssdMedian() : null))
                    .append(", sdsd=").append(valueOf(t.getHrvTimeDomain() != null ? t.getHrvTimeDomain().getSdsdMedian() : null))
                    .append(", pnn50=").append(valueOf(t.getHrvTimeDomain() != null ? t.getHrvTimeDomain().getPnn50Median() : null))
                    .append(", pnn20=").append(valueOf(t.getHrvTimeDomain() != null ? t.getHrvTimeDomain().getPnn20Median() : null))
                    .append(", lfHf=").append(valueOf(t.getHrvFreqDomain() != null ? t.getHrvFreqDomain().getLfHfRatio() : null))
                    .append(", hf=").append(valueOf(t.getHrvFreqDomain() != null ? t.getHrvFreqDomain().getHfAvg() : null))
                    .append(", lf=").append(valueOf(t.getHrvFreqDomain() != null ? t.getHrvFreqDomain().getLfAvg() : null))
                    .append(", vlf=").append(valueOf(t.getHrvFreqDomain() != null ? t.getHrvFreqDomain().getVlfAvg() : null))
                    .append(", tp=").append(valueOf(t.getHrvFreqDomain() != null ? t.getHrvFreqDomain().getTpAvg() : null))
                    .append("\\n");
        }
        return sb.toString();
    }

    private String buildAnalysisSummary(VitalsAnalysisSummary summary) {
        if (summary == null || summary.getMetrics().isEmpty()) {
            return "无规则分析结果。";
        }
        var sb = new StringBuilder();
        for (var metric : summary.getMetrics()) {
            sb.append("- ")
                    .append(metric.getMetricName())
                    .append("(").append(metric.getMetricKey()).append(")")
                    .append(": latest=").append(valueOf(metric.getLatestValue()))
                    .append(", unit=").append(metric.getUnit() == null ? "" : metric.getUnit())
                    .append(", range=").append(metric.getNormalRange())
                    .append(", status=").append(metric.getStatus())
                    .append(", trend=").append(metric.getTrend())
                    .append(", stability=").append(metric.getStability())
                    .append(", slope=").append(valueOf(metric.getSlope()))
                    .append(", std=").append(valueOf(metric.getStandardDeviation()))
                    .append(", samples=").append(metric.getSampleCount())
                    .append("\\n");
        }
        return sb.toString();
    }

    private static String valueOf(Double value) {
        return value == null ? "null" : String.format(Locale.ROOT, "%.4f", value);
    }

    private static String loadTemplate(ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(TEMPLATE_PATH);
        try (var is = resource.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "角色:\n{{ROLE_DESCRIPTION}}\n\n数据摘要:\n{{DATA_SUMMARY}}\n\n规则分析:\n{{ANALYSIS_SUMMARY}}\n\n输出要求:\n{{OUTPUT_REQUIREMENT}}";
        }
    }

    /**
     * 仅负责文本拼装的 Builder，避免 Prompt 结构散落在业务流程中。
     */
    static class PromptTextBuilder {

        private final String template;
        private String role;
        private String dataSummary;
        private String analysisSummary;
        private String outputRequirement;

        PromptTextBuilder(String template) {
            this.template = template;
        }

        PromptTextBuilder role(String role) {
            this.role = role;
            return this;
        }

        PromptTextBuilder dataSummary(String dataSummary) {
            this.dataSummary = dataSummary;
            return this;
        }

        PromptTextBuilder analysisSummary(String analysisSummary) {
            this.analysisSummary = analysisSummary;
            return this;
        }

        PromptTextBuilder outputRequirement(String outputRequirement) {
            this.outputRequirement = outputRequirement;
            return this;
        }

        String build() {
            return template
                    .replace("{{ROLE_DESCRIPTION}}", safe(role))
                    .replace("{{DATA_SUMMARY}}", safe(dataSummary))
                    .replace("{{ANALYSIS_SUMMARY}}", safe(analysisSummary))
                    .replace("{{OUTPUT_REQUIREMENT}}", safe(outputRequirement));
        }

        private String safe(String value) {
            return value == null ? "" : value;
        }
    }
}
