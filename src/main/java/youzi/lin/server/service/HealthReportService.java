package youzi.lin.server.service;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import youzi.lin.server.dto.HealthReportRequest;
import youzi.lin.server.dto.VitalsAnalysisSummary;
import youzi.lin.server.dto.VitalsTrendDto;
import youzi.lin.server.service.report.PromptBuilder;
import youzi.lin.server.service.report.VitalsAnalysisContext;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 健康报告核心业务服务。
 *
 * <p>模板方法流程：数据查询 -> 规则分析 -> Prompt 构建 -> LLM 调用 -> HTML 转换 -> 降级处理。</p>
 */
@Service
public class HealthReportService {

    private static final Logger log = LoggerFactory.getLogger(HealthReportService.class);

    private final PatientVitalsService patientVitalsService;
    private final VitalsAnalysisContext analysisContext;
    private final PromptBuilder promptBuilder;
    private final ChatClient chatClient;
    private final SpringTemplateEngine templateEngine;
    private final Parser parser;
    private final HtmlRenderer htmlRenderer;
    private final long llmTimeoutSeconds;

    public HealthReportService(PatientVitalsService patientVitalsService,
                               VitalsAnalysisContext analysisContext,
                               PromptBuilder promptBuilder,
                               ChatClient.Builder chatClientBuilder,
                               SpringTemplateEngine templateEngine,
                               @Value("${report.llm-timeout-seconds:20}") long llmTimeoutSeconds) {
        this.patientVitalsService = patientVitalsService;
        this.analysisContext = analysisContext;
        this.promptBuilder = promptBuilder;
        this.chatClient = chatClientBuilder.build();
        this.templateEngine = templateEngine;
        this.parser = Parser.builder().build();
        this.htmlRenderer = HtmlRenderer.builder().build();
        this.llmTimeoutSeconds = llmTimeoutSeconds;
    }

    public final String generateReportHtml(HealthReportRequest request) {
        var trends = queryTrendData(request);
        var summary = runRuleAnalysis(trends);

        try {
            String prompt = buildPrompt(trends, summary);
            String markdown = invokeLlm(prompt);
            return toHtml(markdown);
        } catch (Exception ex) {
            log.warn("[Report] LLM 调用失败，进入降级模板渲染: {}", ex.getMessage());
            return renderFallbackHtml(request, summary, ex.getMessage());
        }
    }

    public String renderEmergencyFallback(HealthReportRequest request, Exception ex) {
        var summary = new VitalsAnalysisSummary();
        return renderFallbackHtml(request, summary, ex == null ? "unknown" : ex.getMessage());
    }

    protected List<VitalsTrendDto> queryTrendData(HealthReportRequest request) {
        String interval = request.getInterval();
        String pgInterval = interval == null || interval.isBlank() ? "1 minute" : interval;
        return patientVitalsService.getTrendByBedIdAndPatientId(
                request.getBedId(),
                request.getPatientId(),
                request.getStartTime(),
                request.getEndTime(),
                pgInterval
        );
    }

    protected VitalsAnalysisSummary runRuleAnalysis(List<VitalsTrendDto> trends) {
        return analysisContext.analyzeAll(trends);
    }

    protected String buildPrompt(List<VitalsTrendDto> trends, VitalsAnalysisSummary summary) {
        return promptBuilder.build(trends, summary);
    }

    protected String invokeLlm(String prompt) throws Exception {
        var future = CompletableFuture.supplyAsync(() -> chatClient
                .prompt()
                .user(prompt)
                .call()
                .content());

        String result = future.get(llmTimeoutSeconds, TimeUnit.SECONDS);
        if (result == null || result.isBlank()) {
            throw new IllegalStateException("LLM 返回空内容");
        }
        return result;
    }

    protected String toHtml(String markdown) {
        return htmlRenderer.render(parser.parse(markdown));
    }

    protected String renderFallbackHtml(HealthReportRequest request,
                                        VitalsAnalysisSummary summary,
                                        String errorMessage) {
        Context context = new Context();
        context.setVariable("bedId", request.getBedId());
        context.setVariable("patientId", request.getPatientId());
        context.setVariable("startTime", safeTime(request.getStartTime()));
        context.setVariable("endTime", safeTime(request.getEndTime()));
        context.setVariable("interval", request.getInterval());
        context.setVariable("summary", summary);
        context.setVariable("errorMessage", errorMessage);
        return templateEngine.process("report-fallback", context);
    }

    private String safeTime(Instant time) {
        return time == null ? "" : time.toString();
    }
}
