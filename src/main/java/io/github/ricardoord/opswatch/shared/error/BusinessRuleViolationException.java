package io.github.ricardoord.opswatch.shared.error;

/** The request is valid but breaks a business rule, such as removing the last owner (409). */
public class BusinessRuleViolationException extends DomainException {

    public BusinessRuleViolationException(String detail) {
        super(ProblemCode.BUSINESS_RULE_VIOLATION, detail);
    }
}
