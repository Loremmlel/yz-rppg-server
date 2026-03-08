package youzi.lin.server.config;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ChatClient 与报告相关基础组件配置。
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService reportLlmExecutor() {
        return Executors.newFixedThreadPool(2);
    }

    @Bean
    public Parser markdownParser() {
        return Parser.builder().build();
    }

    @Bean
    public HtmlRenderer markdownHtmlRenderer() {
        return HtmlRenderer.builder().build();
    }
}

