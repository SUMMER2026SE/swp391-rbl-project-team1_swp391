package com.sportvenue.scheduler;

import com.sportvenue.entity.User;
import com.sportvenue.repository.UserRepository;
import com.sportvenue.service.UserPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserPreferenceRefreshScheduler {

    private final UserRepository userRepository;
    private final UserPreferenceService userPreferenceService;

    @Scheduled(cron = "0 0 2 * * *") // 2AM nightly
    @SchedulerLock(name = "UserPreferenceRefreshScheduler_refreshAll", lockAtLeastFor = "PT5M", lockAtMostFor = "PT30M")
    public void refreshAllUserPreferences() {
        log.info("Starting nightly UserPreference refresh job");
        List<User> users = userRepository.findAll();
        for (User user : users) {
            try {
                userPreferenceService.computeForUser(user.getUserId());
            } catch (Exception e) {
                log.error("Failed to compute preference for user {}", user.getUserId(), e);
            }
        }
        log.info("Finished nightly UserPreference refresh job for {} users", users.size());
    }
}
