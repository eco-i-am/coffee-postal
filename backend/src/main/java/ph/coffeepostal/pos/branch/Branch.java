package ph.coffeepostal.pos.branch;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "branches")
public class Branch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true) private String code;
    @Column(nullable = false) private String name;
    private String address;
    private String phone;
    private String tin;
    @Column(name = "vat_registered", nullable = false) private boolean vatRegistered;
    @Column(nullable = false) private boolean active = true;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false) private Instant updatedAt;

    protected Branch() {}
    public Branch(String code, String name, String address, String phone) {
        this.code = code; this.name = name; this.address = address; this.phone = phone;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public String getPhone() { return phone; }
    public String getTin() { return tin; }
    public boolean isVatRegistered() { return vatRegistered; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public void setCode(String code) { this.code = code; }
    public void setName(String name) { this.name = name; }
    public void setAddress(String address) { this.address = address; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setTin(String tin) { this.tin = tin; }
    public void setVatRegistered(boolean vatRegistered) { this.vatRegistered = vatRegistered; }
}
