package youzi.lin.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import youzi.lin.server.entity.AlarmEvent;

import java.util.List;

/**
 * 报警事件仓储。
 * <p>
 * 主要用于护士站调试/回放页面按床位快速拉取最近报警记录。
 * </p>
 */
@Repository
public interface AlarmEventRepository extends JpaRepository<AlarmEvent, Long> {

		/**
		 * 按床位查询最近 50 条报警事件（按触发时间倒序）。
		 */
		List<AlarmEvent> findTop50ByBedIdOrderByTriggerTimeDesc(Long bedId);
}

