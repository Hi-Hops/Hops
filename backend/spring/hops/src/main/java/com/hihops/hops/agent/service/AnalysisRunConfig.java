package com.hihops.hops.agent.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AnalysisRunProperties.class)
class AnalysisRunConfig {
}
