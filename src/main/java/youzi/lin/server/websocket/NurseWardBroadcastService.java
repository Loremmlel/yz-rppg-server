package youzi.lin.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import youzi.lin.server.service.BedWardLookupService;
import youzi.lin.server.service.PatientVitalsService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 护士站病区广播服务：维护订阅关系、快照与增量推送。
 */
@Service
public class NurseWardBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(NurseWardBroadcastService.class);

    private static final long FLUSH_INTERVAL_MS = 300;

    private final WebSocketSessionManager sessionManager;
    private final PatientVitalsService patientVitalsService;
    private final BedWardLookupService bedWardLookupService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** wardCode -> 订阅该病区的 sessionId 集合 */
    private final ConcurrentHashMap<String, Set<String>> wardSubscribers = new ConcurrentHashMap<>();
    /** sessionId -> 该会话订阅的 wardCode 集合 */
    private final ConcurrentHashMap<String, Set<String>> sessionSubscriptions = new ConcurrentHashMap<>();
    /** wardCode -> 待发送增量（同一 patient 仅保留最新值） */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, PendingUpdate>> pendingByWard = new ConcurrentHashMap<>();
    /** wardCode -> 单调版本号（用于前端增量对齐） */
    private final ConcurrentHashMap<String, AtomicLong> wardVersions = new ConcurrentHashMap<>();

    private final AtomicLong seq = new AtomicLong();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "nurse-ward-batch-flusher");
        t.setDaemon(true);
        return t;
    });

    public NurseWardBroadcastService(WebSocketSessionManager sessionManager,
                                     PatientVitalsService patientVitalsService,
                                     BedWardLookupService bedWardLookupService) {
        this.sessionManager = sessionManager;
        this.patientVitalsService = patientVitalsService;
        this.bedWardLookupService = bedWardLookupService;
        scheduler.scheduleAtFixedRate(this::flushBatches, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    public void subscribe(String sessionId, String wardCode) {
        wardSubscribers.computeIfAbsent(wardCode, _ -> ConcurrentHashMap.newKeySet()).add(sessionId);
        sessionSubscriptions.computeIfAbsent(sessionId, _ -> ConcurrentHashMap.newKeySet()).add(wardCode);
    }

    public void unsubscribe(String sessionId, String wardCode) {
        var sessions = wardSubscribers.get(wardCode);
        if (sessions != null) {
            sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                wardSubscribers.remove(wardCode, sessions);
            }
        }

        var wards = sessionSubscriptions.get(sessionId);
        if (wards != null) {
            wards.remove(wardCode);
            if (wards.isEmpty()) {
                sessionSubscriptions.remove(sessionId, wards);
            }
        }
    }

    public void removeSession(String sessionId) {
        var wards = sessionSubscriptions.remove(sessionId);
        if (wards == null || wards.isEmpty()) {
            return;
        }
        for (var wardCode : wards) {
            var sessions = wardSubscribers.get(wardCode);
            if (sessions != null) {
                sessions.remove(sessionId);
                if (sessions.isEmpty()) {
                    wardSubscribers.remove(wardCode, sessions);
                }
            }
        }
    }

    public void publishUpdate(Long bedId, Long patientId, Double hr, Double sqi, Instant eventTime) {
        if (bedId == null || patientId == null) {
            return;
        }

        var wardCode = bedWardLookupService.getWardCodeByBedId(bedId);
        if (wardCode == null) {
            return;
        }

        var subscribers = wardSubscribers.get(wardCode);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }

        var pending = pendingByWard.computeIfAbsent(wardCode, _ -> new ConcurrentHashMap<>());
        pending.put(patientId, new PendingUpdate(patientId, bedId, hr, sqi, eventTime, seq.incrementAndGet()));
    }

    public void sendSnapshot(String sessionId, String wardCode) {
        var snapshot = patientVitalsService.getWardLatestVitalsSnapshot(wardCode);
        ObjectNode msg = objectMapper.createObjectNode();
        msg.put("type", "snapshot");
        msg.put("wardCode", wardCode);
        msg.put("version", wardVersions.computeIfAbsent(wardCode, _ -> new AtomicLong()).get());
        msg.put("generatedAt", Instant.now().toString());

        ArrayNode patients = msg.putArray("patients");
        for (var item : snapshot) {
            ObjectNode p = patients.addObject();
            p.put("patientId", item.patientId());
            p.put("bedId", item.bedId());
            p.put("roomNo", item.roomNo());
            p.put("bedNo", item.bedNo());
            if (item.hr() != null) {
                p.put("hr", item.hr());
            } else {
                p.putNull("hr");
            }
            if (item.sqi() != null) {
                p.put("sqi", item.sqi());
            } else {
                p.putNull("sqi");
            }
            if (item.eventTime() != null) {
                p.put("eventTime", item.eventTime().toString());
            } else {
                p.putNull("eventTime");
            }
        }

        sendJson(sessionId, msg);
    }

    private void flushBatches() {
        for (var entry : pendingByWard.entrySet()) {
            var wardCode = entry.getKey();
            var pending = entry.getValue();

            List<PendingUpdate> updates;
            synchronized (pending) {
                if (pending.isEmpty()) {
                    continue;
                }
                updates = new ArrayList<>(pending.values());
                pending.clear();
            }
            updates.sort(Comparator.comparingLong(PendingUpdate::seq));

            var sessions = wardSubscribers.get(wardCode);
            if (sessions == null || sessions.isEmpty()) {
                continue;
            }

            var versionCounter = wardVersions.computeIfAbsent(wardCode, _ -> new AtomicLong());
            long toVersion = versionCounter.addAndGet(updates.size());
            long fromVersion = toVersion - updates.size() + 1;

            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("type", "vitals.batch_update");
            msg.put("wardCode", wardCode);
            msg.put("fromVersion", fromVersion);
            msg.put("toVersion", toVersion);

            ArrayNode arr = msg.putArray("updates");
            for (var update : updates) {
                ObjectNode item = arr.addObject();
                item.put("patientId", update.patientId());
                item.put("bedId", update.bedId());
                if (update.hr() != null) {
                    item.put("hr", update.hr());
                } else {
                    item.putNull("hr");
                }
                if (update.sqi() != null) {
                    item.put("sqi", update.sqi());
                } else {
                    item.putNull("sqi");
                }
                if (update.eventTime() != null) {
                    item.put("eventTime", update.eventTime().toString());
                } else {
                    item.putNull("eventTime");
                }
                item.put("seq", update.seq());
            }

            String text;
            try {
                text = objectMapper.writeValueAsString(msg);
            } catch (Exception e) {
                log.error("[NurseWS] 序列化病区 {} 增量更新失败: {}", wardCode, e.getMessage(), e);
                continue;
            }

            for (var sessionId : sessions) {
                boolean sent = sessionManager.sendTextMessage(sessionId, text);
                if (!sent) {
                    log.debug("[NurseWS] 会话 {} 不可写，移除订阅", sessionId);
                    removeSession(sessionId);
                }
            }
        }
    }

    private void sendJson(String sessionId, ObjectNode payload) {
        try {
            boolean sent = sessionManager.sendTextMessage(sessionId, objectMapper.writeValueAsString(payload));
            if (!sent) {
                removeSession(sessionId);
            }
        } catch (Exception e) {
            log.error("[NurseWS] 会话 {} 发送 JSON 失败: {}", sessionId, e.getMessage(), e);
        }
    }

    private record PendingUpdate(Long patientId,
                                 Long bedId,
                                 Double hr,
                                 Double sqi,
                                 Instant eventTime,
                                 long seq) {
    }
}



