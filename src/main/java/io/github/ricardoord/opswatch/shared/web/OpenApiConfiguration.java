package io.github.ricardoord.opswatch.shared.web;

import io.github.ricardoord.opswatch.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;

/**
 * API-wide OpenAPI settings. Endpoints that need an access token declare {@code @SecurityRequirement(name = BEARER)},
 * so Swagger UI can send it.
 */
@Configuration(proxyBeanMethods = false)
@SecurityScheme(
        name = OpenApiConfiguration.BEARER,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfiguration {

    public static final String BEARER = "bearer-jwt";

    static {
        // Resolved from the token, not sent by the client: it must not appear as a request parameter
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(CurrentUser.class);
    }
}
