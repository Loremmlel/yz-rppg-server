package youzi.lin.server.enums;

/**
 * 住院就诊状态枚举。
 */
public enum VisitStatus {
    /** 在院中（正在住院） */
    ADMITTED,
    /** 已出院 */
    DISCHARGED,
    /** 已转科/转院 */
    TRANSFERRED,
    /** 已取消（未实际入住） */
    CANCELED
}
