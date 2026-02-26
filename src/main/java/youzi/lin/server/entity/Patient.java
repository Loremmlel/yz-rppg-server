package youzi.lin.server.entity;

import jakarta.persistence.*;
import youzi.lin.server.enums.Gender;

@Entity
@Table(name = "patient")
public class Patient {
    /**
     * 患者id，最好接入医院信息系统，毕设当然是构造一个简单的完整系统
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @Enumerated(EnumType.STRING)
    private Gender gender;
    private String idCardNo;
    private String phoneNo;
}
