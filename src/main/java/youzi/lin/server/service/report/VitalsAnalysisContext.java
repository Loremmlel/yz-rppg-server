package youzi.lin.server.service.report;

import org.springframework.stereotype.Component;
import youzi.lin.server.dto.VitalsAnalysisSummary;
import youzi.lin.server.dto.VitalsTrendDto;

import java.util.Comparator;
import java.util.List;

/**
 * 指标分析上下文工厂：根据注入的策略集合执行统一分析。
 */
@Component
public class VitalsAnalysisContext {

    private final List<VitalsAnalyzer> analyzers;

    public VitalsAnalysisContext(List<VitalsAnalyzer> analyzers) {
        this.analyzers = analyzers;
    }

    public VitalsAnalysisSummary analyzeAll(List<VitalsTrendDto> trends) {
        var summary = new VitalsAnalysisSummary();
        analyzers.stream()
                .sorted(Comparator.comparing(VitalsAnalyzer::metricKey))
                .map(analyzer -> analyzer.analyze(trends))
                .forEach(summary::addMetric);
        return summary;
    }
}

