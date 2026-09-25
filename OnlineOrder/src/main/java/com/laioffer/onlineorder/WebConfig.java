package com.laioffer.onlineorder;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Caching for the built frontend. Files under /assets have a content hash in their name, so a
 * browser may keep them for a year; a new build produces new names. index.html is not cached,
 * so a deploy reaches every browser on its next page load.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/public/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/index.html")
                .addResourceLocations("classpath:/public/")
                .setCacheControl(CacheControl.noCache());
    }
}
