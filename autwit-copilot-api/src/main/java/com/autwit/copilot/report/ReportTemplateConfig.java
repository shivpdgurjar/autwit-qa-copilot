package com.autwit.copilot.report;

import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ITemplateResolver;

/**
 * A TEXT-mode resolver for the markdown report, alongside Boot's default HTML one.
 *
 * <p>Rendering markdown through the HTML resolver would escape every {@code &} and
 * {@code <} in the report's own data and wrap nothing in tags — the output would look
 * almost right and be subtly corrupt. TEXT mode leaves the content alone.
 *
 * <p>Scoped by {@code resolvablePatterns} to {@code *-md} so it never shadows
 * report.html. Boot collects every ITemplateResolver bean into the engine, and
 * resolvers are consulted in order, so an unscoped TEXT resolver at order 0 would try
 * to answer for every template.
 */
@Configuration
public class ReportTemplateConfig {

    @Bean
    ITemplateResolver markdownTemplateResolver() {
        var resolver = new SpringResourceTemplateResolver();
        resolver.setPrefix("classpath:/templates/");
        resolver.setSuffix(".txt");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setResolvablePatterns(Set.of("*-md"));
        resolver.setCheckExistence(true);
        // Ahead of Boot's HTML resolver (order 1), but only for *-md.
        resolver.setOrder(0);
        resolver.setCacheable(true);
        return resolver;
    }
}
