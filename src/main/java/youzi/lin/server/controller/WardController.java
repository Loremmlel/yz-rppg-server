package youzi.lin.server.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import youzi.lin.server.dto.BedDetailDto;
import youzi.lin.server.dto.RoomDto;
import youzi.lin.server.dto.WardDto;
import youzi.lin.server.service.WardService;

import java.util.List;

/**
 * 病区/病房/床位查询接口（只读，客户端不操作住院出院）
 * GET /api/wards                                  → 所有病区（含各病区病房、床位及在住患者）
 * GET /api/wards/{wardCode}/rooms                 → 指定病区的所有病房（含床位及在住患者）
 * GET /api/wards/{wardCode}/rooms/{roomNo}/beds   → 指定病房内所有床位（含在住患者）
 */
@RestController
@RequestMapping("/api/wards")
public class WardController {

    private final WardService wardService;

    public WardController(WardService wardService) {
        this.wardService = wardService;
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
}

