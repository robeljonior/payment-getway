package com.superup.gateway.gateway;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

/** The order of the layer 1 checks: first a request ID, then the API key and rate limits. */
@Configuration
public class FilterConfig {

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> bean = new FilterRegistrationBean<>(new RequestIdFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.addUrlPatterns("/*");
        return bean;
    }

    @Bean
    FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(JdbcTemplate db, RateLimiter limiter, ErrorWriter errors) {
        FilterRegistrationBean<ApiKeyAuthFilter> bean =
                new FilterRegistrationBean<>(new ApiKeyAuthFilter(db, limiter, errors));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        bean.addUrlPatterns("/api/v1/*");
        return bean;
    }
}
