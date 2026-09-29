package io.github.ricardoord.opswatch.shared.error;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Spring Security handlers that delegate to Spring MVC's exception resolution, so {@code 401} and {@code 403}
 * responses are produced by {@link ProblemDetailsHandler} in exactly the same format as every other error. They are
 * plugged into the {@code SecurityFilterChain} by the identity module.
 */
@Configuration(proxyBeanMethods = false)
public class ProblemDetailsSecurityHandlers {

    @Bean
    AuthenticationEntryPoint problemDetailsAuthenticationEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, exception) -> resolver.resolveException(request, response, null, exception);
    }

    @Bean
    AccessDeniedHandler problemDetailsAccessDeniedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, exception) -> resolver.resolveException(request, response, null, exception);
    }
}
