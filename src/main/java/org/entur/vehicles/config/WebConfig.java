package org.entur.vehicles.config;

import org.springframework.boot.tomcat.TomcatConnectorCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class WebConfig {

    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration().applyPermitDefaultValues();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }

    /**
     * Tomcat rejects requests with more than 10 Accept-Language entries by default. Spring's
     * RequestContextFilter reads the locale on every request, so such a request failed with a 500
     * before reaching the application. The locale is never used here; 50 lets real clients through
     * while keeping a bound on the parsing.
     */
    @Bean
    public TomcatConnectorCustomizer acceptLanguageLimit() {
        return connector -> connector.setMaxLocaleCount(50);
    }
}