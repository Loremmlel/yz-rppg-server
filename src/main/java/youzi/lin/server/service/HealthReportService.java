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

    /**
     * 生成完整的健康报告 HTML。
     * <p>
     * 模板方法流程：数据查询 → 规则分析 → Prompt 构建 → LLM 调用 → Markdown 转 HTML。
     * LLM 相关步骤抛出异常时，自动降级为基于规则分析的静态模板。
     * </p>
     *
     * @param request 报告请求参数（已通过 Controller 校验）
     * @return 完整的 HTML 字符串
     */
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

    /**
     * 紧急降级：当 Controller 层捕获到未预期异常时调用，生成不含规则分析数据的空降级报告。
     *
     * @param request 原始请求（用于在报告中展示基本信息）
     * @param ex      导致降级的异常，可为 {@code null}
     */
    public String renderEmergencyFallback(HealthReportRequest request, Exception ex) {
        var summary = new VitalsAnalysisSummary();
        return renderFallbackHtml(request, summary, ex == null ? "unknown" : ex.getMessage());
    }

    /**
     * 查询指定时间范围内的趋势聚合数据（可被子类覆盖用于测试）。
     */
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

    /**
     * 对趋势数据执行规则引擎分析，生成各指标的状态/趋势/稳定性汇总（可被子类覆盖用于测试）。
     */
    protected VitalsAnalysisSummary runRuleAnalysis(List<VitalsTrendDto> trends) {
        return analysisContext.analyzeAll(trends);
    }

    /**
     * 根据趋势数据和规则分析结果构建 LLM Prompt（可被子类覆盖用于测试）。
     */
    protected String buildPrompt(List<VitalsTrendDto> trends, VitalsAnalysisSummary summary) {
        return promptBuilder.build(trends, summary);
    }

    /**
     * 同步调用 LLM 并等待结果，超时时间由配置项 {@code report.llm-timeout-seconds} 控制。
     *
     * @throws Exception LLM 调用超时、返回空内容或其他 I/O 异常
     */
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

    /**
     * 将 LLM 返回的 Markdown 字符串渲染为 HTML 片段（可被子类覆盖用于测试）。
     */
    protected String toHtml(String markdown) {
        return htmlRenderer.render(parser.parse(markdown));
    }

    /**
     * 使用 Thymeleaf 降级模板渲染报告，将规则分析结果直接呈现给用户，
     * 并在页面中展示错误原因，方便排查 LLM 调用失败的原因。
     */
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
