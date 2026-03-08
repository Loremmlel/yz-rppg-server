package youzi.lin.server.service.report;

import org.springframework.stereotype.Component;
import youzi.lin.server.dto.VitalsAnalysisSummary;
import youzi.lin.server.dto.VitalsTrendDto;

import java.util.Comparator;
import java.util.List;

/**
 * 指标分析上下文：统一编排并执行所有 {@link VitalsAnalyzer} 策略。
 * <p>
 * 通过 Spring 自动注入所有 {@link VitalsAnalyzer} 实现，无需手动注册；
 * 新增指标分析器只需声明为 {@code @Component} 即可自动生效。
 * </p>
 */
@Component
public class VitalsAnalysisContext {

    private final List<VitalsAnalyzer> analyzers;

    public VitalsAnalysisContext(List<VitalsAnalyzer> analyzers) {
        this.analyzers = analyzers;
    }

    /**
     * 对所有已注册的分析器依次执行分析，并将结果汇总返回。
     * <p>
     * 按 {@link VitalsAnalyzer#metricKey()} 排序，确保每次生成的报告中指标顺序一致，
     * 方便 LLM 对比相邻时间段的报告。
     * </p>
     *
     * @param trends 时间窗口聚合趋势数据
     * @return 各指标分析结果汇总
     */
    public VitalsAnalysisSummary analyzeAll(List<VitalsTrendDto> trends) {
        var summary = new VitalsAnalysisSummary();
        analyzers.stream()
                .sorted(Comparator.comparing(VitalsAnalyzer::metricKey))
                .map(analyzer -> analyzer.analyze(trends))
                .forEach(summary::addMetric);
        return summary;
    }
}

