package io.github.ricardoord.opswatch.identity.web;

import io.github.ricardoord.opswatch.identity.domain.PasswordRules;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.jspecify.annotations.Nullable;

/** The password follows {@link PasswordRules}. A null value is left to {@code @NotNull}. */
@Documented
@Constraint(validatedBy = PasswordPolicy.Validator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordPolicy {

    String message() default "must have at least 12 characters and at most 72 bytes in UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<PasswordPolicy, String> {

        @Override
        public boolean isValid(@Nullable String password, ConstraintValidatorContext context) {
            return password == null || PasswordRules.accepts(password);
        }
    }
}
