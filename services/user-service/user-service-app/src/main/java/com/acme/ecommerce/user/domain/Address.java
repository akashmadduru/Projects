package com.acme.ecommerce.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A saved shipping address owned by exactly one user. */
@Entity
@Table(name = "addresses")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "label", length = 60)
  private String label;

  @Column(name = "full_name", nullable = false, length = 120)
  private String fullName;

  @Column(name = "phone", length = 32)
  private String phone;

  @Column(name = "line1", nullable = false, length = 200)
  private String line1;

  @Column(name = "line2", length = 200)
  private String line2;

  @Column(name = "city", nullable = false, length = 100)
  private String city;

  @Column(name = "state", length = 100)
  private String state;

  @Column(name = "postal_code", nullable = false, length = 20)
  private String postalCode;

  @Column(name = "country", nullable = false, columnDefinition = "char(2)")
  private String country;

  @Column(name = "is_default", nullable = false)
  private boolean defaultAddress;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  public static Address forUser(UUID userId, Instant now) {
    Address address = new Address();
    address.userId = userId;
    address.createdAt = now;
    address.updatedAt = now;
    return address;
  }
}
