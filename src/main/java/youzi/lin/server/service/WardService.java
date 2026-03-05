package youzi.lin.server.service;

import org.springframework.stereotype.Service;
import youzi.lin.server.dto.BedDetailDto;
import youzi.lin.server.dto.PatientBriefDto;
import youzi.lin.server.dto.RoomDto;
import youzi.lin.server.dto.WardBriefDto;
import youzi.lin.server.dto.WardDto;
import youzi.lin.server.entity.Bed;
import youzi.lin.server.enums.VisitStatus;
import youzi.lin.server.repository.BedRepository;
import youzi.lin.server.repository.VisitRepository;

import java.util.List;
import java.util.Optional;

@Service
public class WardService {

    private final BedRepository bedRepository;
    private final VisitRepository visitRepository;

    public WardService(BedRepository bedRepository, VisitRepository visitRepository) {
        this.bedRepository = bedRepository;
        this.visitRepository = visitRepository;
    }

    /**
     * 获取所有病区代码列表（不含病房和床位详情，用于客户端选择病区）
     */
    public List<WardBriefDto> getWardList() {
        return bedRepository.findDistinctWardCodes().stream()
                .map(WardBriefDto::new)
                .toList();
    }

    /**
     * 获取所有病区（含各病区下所有病房及床位详情）
     */
    public List<WardDto> getAllWards() {
        var wardCodes = bedRepository.findDistinctWardCodes();
        return wardCodes.stream()
                .map(wardCode -> new WardDto(wardCode, getRoomsInWard(wardCode)))
                .toList();
    }

    /**
     * 获取指定病区的所有病房（含各病房内床位详情）
     */
    public List<RoomDto> getRoomsInWard(String wardCode) {
        var roomNos = bedRepository.findDistinctRoomNosByWardCode(wardCode);
        return roomNos.stream()
                .map(roomNo -> new RoomDto(roomNo, getBedsInRoom(wardCode, roomNo)))
                .toList();
    }

    /**
     * 获取指定病区、病房内所有床位详情（含在住患者信息）
     */
    public List<BedDetailDto> getBedsInRoom(String wardCode, String roomNo) {
        var beds = bedRepository.findByWardCodeAndRoomNo(wardCode, roomNo);
        return beds.stream()
                .map(this::toBedDetailDto)
                .toList();
    }

    /**
     * 通过床位 ID 查询当前在住患者信息。
     * 若床位不存在或当前无在住患者，返回 Optional.empty()。
     */
    public Optional<PatientBriefDto> getCurrentPatientByBedId(Long bedId) {
        return visitRepository.findByBedIdAndStatus(bedId, VisitStatus.VISITED)
                .map(visit -> {
                    var patient = visit.getPatient();
                    return new PatientBriefDto(patient.getId(), patient.getName(), patient.getGender().name());
                });
    }

    /**
     * 将 Bed 实体转换为 BedDetailDto，如果床位被占用则查询并填充当前患者信息
     */
    private BedDetailDto toBedDetailDto(Bed bed) {
        var activeVisit = visitRepository.findByBedIdAndStatus(bed.getId(), VisitStatus.VISITED);
        var patientDto = activeVisit.map(visit -> {
            var patient = visit.getPatient();
            return new PatientBriefDto(patient.getId(), patient.getName(), patient.getGender().name());
        }).orElse(null);

        return new BedDetailDto(
                bed.getId(),
                bed.getBedNo(),
                bed.getDeviceSn(),
                bed.getStatus().name(),
                patientDto
        );
    }
}
