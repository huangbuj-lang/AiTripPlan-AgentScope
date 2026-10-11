package managerAgent.service;

import jakarta.annotation.Resource;
import managerAgent.repository.NotificationRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 通知中心业务：通知列表、未读数量、全部标记已读。
 * 只做业务，SQL 在 NotificationRepository。
 */
@Service
public class NotificationService {

    @Resource
    private NotificationRepository notificationRepository;

    /** 通知列表（分页） */
    public Map<String, Object> list(long userId, int page, int pageSize) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("notifications", notificationRepository.listByUser(userId, page, pageSize));
        return result;
    }

    /** 未读数量 */
    public Map<String, Object> unreadCount(long userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("count", notificationRepository.countUnread(userId));
        return result;
    }

    /** 全部标记已读 */
    public Map<String, Object> markAllRead(long userId) {
        notificationRepository.markAllRead(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }
}
