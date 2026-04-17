package youzi.lin.server.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import youzi.lin.server.repository.BedRepository;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 床位到病区代码的缓存查询服务。
 * <p>
 * 用于护士站高频推送路径，避免每次增量下发都访问数据库。
 * </p>
 */
@Service
public class BedWardLookupService {

    private final BedRepository bedRepository;
    private final ConcurrentHashMap<Long, String> bedWardCache = new ConcurrentHashMap<>();

    public BedWardLookupService(BedRepository bedRepository) {
        this.bedRepository = bedRepository;
    }

    /**
     * 应用启动时预热床位到病区映射缓存。
     */
    @PostConstruct
    void warmup() {
        bedRepository.findAll().forEach(bed -> bedWardCache.put(bed.getId(), bed.getWardCode()));
    }

    /**
     * 根据床位 ID 查询病区编码。
     * <p>
     * 先读内存缓存，未命中时回源数据库并写回缓存。
     * </p>
     */
    public String getWardCodeByBedId(Long bedId) {
        if (bedId == null) {
            return null;
        }
        return bedWardCache.computeIfAbsent(
                bedId,
                id -> bedRepository.findById(id).map(youzi.lin.server.entity.Bed::getWardCode).orElse(null)
        );
    }
}

