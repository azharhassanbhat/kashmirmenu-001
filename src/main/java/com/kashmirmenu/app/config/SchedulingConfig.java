package com.kashmirmenu.app.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled jobs (the idle-table check). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
