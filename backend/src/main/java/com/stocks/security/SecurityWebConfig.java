package com.stocks.security;

import com.stocks.common.time.TimeSource;
import com.stocks.config.StocksProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link RecentAuthInterceptor} for {@code /api/**} (it only acts on {@link RecentAuth} handlers). */
@Configuration(proxyBeanMethods = false)
public class SecurityWebConfig implements WebMvcConfigurer {

  private final RecentAuthInterceptor recentAuth;

  public SecurityWebConfig(TimeSource time, StocksProperties properties) {
    this.recentAuth = new RecentAuthInterceptor(time, properties);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(recentAuth).addPathPatterns("/api/**");
  }
}
