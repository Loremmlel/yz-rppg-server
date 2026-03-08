package youzi.lin.server.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import youzi.lin.server.dto.BedDetailDto;
import youzi.lin.server.dto.PatientBriefDto;
import youzi.lin.server.dto.RoomDto;
import youzi.lin.server.dto.WardBriefDto;
import youzi.lin.server.dto.WardDto;
import youzi.lin.server.service.WardService;

import java.util.List;

/**
 * 病区/病房/床位查询接口（只读，住院出院操作由 HIS 负责）。
 *
 * <ul>
 *   <li>{@code GET /api/wards/list}                           — 所有病区代码列表（轻量，用于选择病区）</li>
 *   <li>{@code GET /api/wards}                                — 所有病区（含各病区病房、床位及在住患者）</li>
 *   <li>{@code GET /api/wards/{wardCode}/rooms}               — 指定病区的所有病房（含床位及在住患者）</li>
 *   <li>{@code GET /api/wards/{wardCode}/rooms/{roomNo}/beds} — 指定病房内所有床位（含在住患者）</li>
 *   <li>{@code GET /api/wards/beds/{bedId}/patient}           — 指定床位当前在住患者信息</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/wards")
public class WardController {

    private final WardService wardService;

    public WardController(WardService wardService) {
        this.wardService = wardService;
    }

    /**
     * 获取所有病区代码列表（轻量接口，不含病房和床位详情）。
     * <p>
     * 客户端通常先调用此接口填充病区选择框，再按需加载详细数据，以减少首屏流量。
     * </p>
     */
    @GetMapping("/list")
    public ResponseEntity<List<WardBriefDto>> getWardList() {
        return ResponseEntity.ok(wardService.getWardList());
    }

    /**
     * 获取所有病区信息（含病房、床位、患者）
     */
    @GetMapping
    public ResponseEntity<List<WardDto>> getAllWards() {
        return ResponseEntity.ok(wardService.getAllWards());
    }

    /**
     * 获取指定病区下所有病房（含床位、患者）
     */
    @GetMapping("/{wardCode}/rooms")
    public ResponseEntity<List<RoomDto>> getRoomsInWard(@PathVariable String wardCode) {
        var rooms = wardService.getRoomsInWard(wardCode);
        if (rooms.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(rooms);
    }

    /**
     * 获取指定病区指定病房内所有床位（含患者）
     */
    @GetMapping("/{wardCode}/rooms/{roomNo}/beds")
    public ResponseEntity<List<BedDetailDto>> getBedsInRoom(
            @PathVariable String wardCode,
            @PathVariable String roomNo) {
        var beds = wardService.getBedsInRoom(wardCode, roomNo);
        if (beds.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(beds);
    }

    /**
     * 通过床位 ID 查询当前在住患者信息。
     * 若床位当前无在住患者，返回 404。
     */
    @GetMapping("/beds/{bedId}/patient")
    public ResponseEntity<PatientBriefDto> getCurrentPatientByBedId(@PathVariable Long bedId) {
        return wardService.getCurrentPatientByBedId(bedId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}

