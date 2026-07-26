package com.sportvenue.repository;

import com.sportvenue.entity.AiNotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AiNotificationLogRepository extends JpaRepository<AiNotificationLog, Long> {
    List<AiNotificationLog> findByUserUserIdOrderByCreatedAtDesc(Integer userId);
    int countByUserUserIdAndCreatedAtAfter(Integer userId, java.time.LocalDateTime after);
    int countByUserUserIdAndGeneratedByLlmTrueAndCreatedAtAfter(Integer userId, java.time.LocalDateTime after);
}
