package youzi.lin.server.config;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 聊天客户端与报告相关基础组件配置。
 * <p>
 * 提供 {@link ChatClient}、LLM 专用线程池以及 Markdown 解析/渲染器的 Bean 定义。
 * </p>
 */
@Configuration
public class ChatClientConfig {

    /**
     * 构建默认配置的 {@link ChatClient}，由 Spring AI 自动读取 {@code application.yaml}
     * 中的模型参数（baseUrl、model、apiKey 等）。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    /**
     * LLM 调用专用固定线程池（2 线程）。
     * <p>
     * 独立线程池的目的是隔离 LLM 的长耗时调用，避免占用 Web 请求线程，
     * 容量设为 2 是因为并发生成报告的场景较少，且 LLM 本身是 I/O 密集型。
     * </p>
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService reportLlmExecutor() {
        return Executors.newFixedThreadPool(2);
    }

    /**
     * Flexmark Markdown 解析器，用于将 LLM 返回的 Markdown 文本转换为 HTML。
     */
    @Bean
    public Parser markdownParser() {
        return Parser.builder().build();
    }

    /**
     * Flexmark HTML 渲染器，与 {@link #markdownParser()} 配合使用。
     */
    @Bean
    public HtmlRenderer markdownHtmlRenderer() {
        return HtmlRenderer.builder().build();
    }
}
