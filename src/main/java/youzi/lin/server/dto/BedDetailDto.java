package youzi.lin.server.dto;

/**
 * 床位详情 DTO：包含床位基本信息、状态，以及当前在住患者（若有）
 */
public class BedDetailDto {
    private Long id;
    private String bedNo;
    private String deviceSn;
    /** EMPTY / OCCUPIED / MAINTAINING / RESERVED */
    private String status;
    /** 当前在住患者，status=OCCUPIED 时不为 null */
    private PatientBriefDto currentPatient;

    public BedDetailDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getBedNo() { return bedNo; }
    public void setBedNo(String bedNo) { this.bedNo = bedNo; }

    public String getDeviceSn() { return deviceSn; }
    public void setDeviceSn(String deviceSn) { this.deviceSn = deviceSn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public PatientBriefDto getCurrentPatient() { return currentPatient; }
    public void setCurrentPatient(PatientBriefDto currentPatient) { this.currentPatient = currentPatient; }
}

