package com.example.langchain4jdemo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置 —— 给 {@code dashboard/} 下的可视化对照台用。
 *
 * <p>和 spring-ai-demo 侧的 {@code WebConfig} 是同一个东西，写两遍是因为
 * 两个项目<b>各自独立、互不依赖</b>（对照它们的差异是这套项目的全部意义，
 * 所以不能让它们共享代码，否则对照就失去可信度）。
 *
 * <p>对照台跑在本机另一个端口上，要同时调 8081 和 8082，所以两侧都得放行。
 * 关于 {@code allowedOriginPatterns} 和 {@code allowedOrigins} 的区别、
 * 以及为什么不用 {@code static/} 托管页面，见 spring-ai-demo 侧同名类的注释。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOriginPatterns;

    /**
     * 默认放行本机任意端口，外加 {@code null}（对应 file:// 直接打开页面的情况）。
     * 详见 spring-ai-demo 侧 {@code WebConfig} 的注释。
     */
    public WebConfig(@Value("${app.cors.allowed-origin-patterns:http://localhost:[*],http://127.0.0.1:[*],null}") String patterns) {
        this.allowedOriginPatterns = patterns.split(",");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
