package youzi.lin.server.entity;

import jakarta.persistence.*;
import youzi.lin.server.enums.Gender;

/**
 * 患者实体，对应数据库表 {@code patient}。
 * <p>
 * 毕设场景下患者信息由系统直接管理；
 * 生产环境应对接医院 HIS（医院信息系统）获取患者主索引。
 * </p>
 */
@Entity
@Table(name = "patient")
public class Patient {
    /**
     * 患者主键 ID。生产环境宜替换为 HIS 患者主索引，避免重复建档。
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    /** 身份证号（唯一索引，可用于跨次就诊关联） */
    private String idCardNo;

    private String phoneNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Gender getGender() { return gender; }
    public void setGender(Gender gender) { this.gender = gender; }

    public String getIdCardNo() { return idCardNo; }
    public void setIdCardNo(String idCardNo) { this.idCardNo = idCardNo; }

    public String getPhoneNo() { return phoneNo; }
    public void setPhoneNo(String phoneNo) { this.phoneNo = phoneNo; }
}
