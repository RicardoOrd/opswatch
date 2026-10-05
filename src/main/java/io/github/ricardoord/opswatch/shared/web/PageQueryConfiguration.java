package io.github.ricardoord.opswatch.shared.web;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Lets any controller declare a {@link PageQuery} or a {@link CursorQuery} parameter. */
@Configuration(proxyBeanMethods = false)
public class PageQueryConfiguration implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PageQueryArgumentResolver());
        resolvers.add(new CursorQueryArgumentResolver());
    }
}
