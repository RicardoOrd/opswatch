package io.github.ricardoord.opswatch.monitoring.domain;

/** The HTTP methods a check may use: only those without a body and without side effects on the target. */
public enum ProbeMethod {
    GET,
    HEAD
}
