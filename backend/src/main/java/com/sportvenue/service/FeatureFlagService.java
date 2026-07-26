package com.sportvenue.service;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Getter
public class FeatureFlagService {
    @Value("${feature.proactive-notifications:false}")
    private boolean proactiveNotifications;

    @Value("${feature.personalization:false}")
    private boolean personalization;

    @Value("${feature.compound-task:false}")
    private boolean compoundTask;
}
