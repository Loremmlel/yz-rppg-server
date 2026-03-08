package youzi.lin.server.enums;

/**
 * 床位状态枚举。
 */
public enum BedStatus {
    /** 空置（可分配） */
    EMPTY,
    /** 已占用（有患者在住） */
    OCCUPIED,
    /** 维护中（暂不可用） */
    MAINTAINING,
    /** 已预留（待入院） */
    RESERVED
}
