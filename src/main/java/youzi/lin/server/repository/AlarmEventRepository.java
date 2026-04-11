package youzi.lin.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import youzi.lin.server.entity.AlarmEvent;

/**
 * 报警事件仓储。
 */
@Repository
public interface AlarmEventRepository extends JpaRepository<AlarmEvent, Long> {

	java.util.List<AlarmEvent> findTop50ByBedIdOrderByTriggerTimeDesc(Long bedId);
}

