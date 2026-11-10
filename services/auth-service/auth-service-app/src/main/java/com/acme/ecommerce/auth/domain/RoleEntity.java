package com.acme.ecommerce.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * A grantable role.
 *
 * <p>Reference data, seeded by the baseline migration and never written at runtime - which is why
 * there is no id generator and no setter for {@link #getId()}.
 *
 * <p>{@link #getName()} is the PREFIXED authority form ({@code ROLE_ADMIN}). The unprefixed name is
 * what goes into the JWT {@code roles} claim; see {@code com.acme.ecommerce.common.security.Roles}
 * for why both spellings exist.
 */
@Entity
@Table(name = "roles")
public class RoleEntity {

  @Id
  @Column(name = "id", nullable = false)
  private Short id;

  @Column(name = "name", nullable = false, length = 50)
  private String name;

  @Column(name = "description", length = 255)
  private String description;

  protected RoleEntity() {
    // JPA
  }

  public Short getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  /**
   * The name as it appears in the JWT {@code roles} claim: {@code ROLE_ADMIN} becomes {@code
   * ADMIN}.
   */
  public String claimName() {
    return name != null && name.startsWith("ROLE_") ? name.substring("ROLE_".length()) : name;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof RoleEntity role && id != null && id.equals(role.id);
  }

  @Override
  public int hashCode() {
    // Constant hash: the id is assigned, never generated, but a constant keeps
    // the contract stable even if that ever changes. Role sets are tiny, so the
    // hash-bucket collision cost is irrelevant.
    return Objects.hashCode(RoleEntity.class);
  }

  @Override
  public String toString() {
    return "RoleEntity[id=" + id + ", name=" + name + "]";
  }

}
