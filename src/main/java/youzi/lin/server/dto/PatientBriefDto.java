package youzi.lin.server.dto;

/**
 * 患者简要信息，用于床位详情中展示
 */
public class PatientBriefDto {
    private Long id;
    private String name;
    private String gender;

    public PatientBriefDto() {}

    public PatientBriefDto(Long id, String name, String gender) {
        this.id = id;
        this.name = name;
        this.gender = gender;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
}

