package io.github.ricardoord.opswatch.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} for the background jobs of every module. With virtual threads enabled, each run gets its
 * own thread, so no scheduler pool needs sizing (application.yml).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {}
