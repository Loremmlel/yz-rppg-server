package youzi.lin.server.service;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import youzi.lin.server.repository.BedRepository;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 床位到病区代码的缓存查询服务，避免高频推送路径反复查库。
 */
@Service
public class BedWardLookupService {

    private final BedRepository bedRepository;
    private final ConcurrentHashMap<Long, String> bedWardCache = new ConcurrentHashMap<>();

    public BedWardLookupService(BedRepository bedRepository) {
        this.bedRepository = bedRepository;
    }

    @PostConstruct
    void warmup() {
        bedRepository.findAll().forEach(bed -> bedWardCache.put(bed.getId(), bed.getWardCode()));
    }

    public String getWardCodeByBedId(Long bedId) {
        if (bedId == null) {
            return null;
        }
        return bedWardCache.computeIfAbsent(
                bedId,
                id -> bedRepository.findById(id).map(bed -> bed.getWardCode()).orElse(null)
        );
    }
}

