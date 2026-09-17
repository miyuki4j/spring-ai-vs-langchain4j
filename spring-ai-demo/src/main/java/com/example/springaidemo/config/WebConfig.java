package com.example.springaidemo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置 —— 给 {@code dashboard/} 下的可视化对照台用。
 *
 * <p>对照台是一个<b>独立的静态页面</b>（不打包进这个 Spring Boot 应用），
 * 由本机另一个端口提供（默认 8090）。在浏览器同源策略下，
 * 「页面在 8090、接口在 8081」算跨域，必须由服务端显式放行，
 * 否则 fetch 会直接被浏览器拦掉，连请求都发不出去。
 *
 * <p><b>为什么不把页面塞进 {@code src/main/resources/static/}？</b>
 * 那样确实同源、零配置，但对照台要<b>同时</b>调 8081 和 8082 两个后端 ——
 * 塞进任何一侧，对另一侧仍然是跨域，只是把问题挪了个位置。
 * 所以两边对称地各放行一次，页面独立部署，职责更清楚。
 *
 * <p><b>为什么是 {@code allowedOriginPatterns} 而不是 {@code allowedOrigins}？</b>
 * 后者不支持通配符，写 {@code http://localhost:*} 会在启动时抛
 * {@code IllegalArgumentException}。这是 Spring 有意的安全设计：
 * 带通配的 Origin 一旦被写进 {@code Access-Control-Allow-Origin}，就等于对所有来源开放，
 * 而 {@code Access-Control-Allow-Credentials} 是 {@code true} 时更危险。
 * 用 Pattern 版本时 Spring 会逐条回显实际匹配到的 Origin，而不是回显通配符本身。
 *
 * <p>默认只放行本机来源。要收紧（或换成别的地址），改 application.yml 即可：
 * <pre>
 * app:
 *   cors:
 *     allowed-origin-patterns: https://your-domain.example
 * </pre>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String[] allowedOriginPatterns;

    /**
     * 默认放行本机任意端口，外加 {@code null}。
     *
     * <p>{@code null} 对应的是「直接双击 index.html、用 file:// 协议打开」的场景 ——
     * 此时浏览器发出的 Origin 头就是字符串 {@code null}。放行它，页面就能双击即用，
     * 不必非得先起一个静态服务器。
     */
    public WebConfig(@Value("${app.cors.allowed-origin-patterns:http://localhost:[*],http://127.0.0.1:[*],null}") String patterns) {
        this.allowedOriginPatterns = patterns.split(",");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 只对 /api/** 开跨域，静态资源和其他路径不动
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOriginPatterns)
                // 对照台只读数据，用不到 POST/PUT；预检请求（OPTIONS）是浏览器自动发的
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("*")
                // 预检结果缓存 1 小时，少发点 OPTIONS
                .maxAge(3600);
    }
}
