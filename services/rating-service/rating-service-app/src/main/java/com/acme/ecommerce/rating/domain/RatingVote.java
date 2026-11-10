package com.acme.ecommerce.rating.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * User votes on whether a rating was helpful or unhelpful.
 *
 * <p>UNIQUE CONSTRAINT on (rating_id, user_id) makes vote addition idempotent: a user can change
 * their vote (helpful -> unhelpful, vice versa) but only one vote per rating per user can exist.
 *
 * <p>NO {@code @PrePersist}, AND NO {@code Instant.now()} ANYWHERE IN THIS CLASS - mirroring
 * {@link Rating}: {@code now} is an explicit parameter callers pass to {@link #builder()}.
 *
 * <p>EQUALITY IS KEYED ON {@code id} ONLY (same pattern, same caveat, as {@link Rating}).
 * Construction goes through Lombok's generated builder, never a hand-rolled constructor.
 */
@Entity
@Table(
    name = "rating_votes",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"rating_id", "user_id"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class RatingVote {

  @EqualsAndHashCode.Include
  @ToString.Include
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** FK to rating.ratings. Deletes cascade so votes are cleaned up when a rating is deleted. */
  @ToString.Include
  @Column(name = "rating_id", nullable = false, updatable = false)
  private UUID ratingId;

  /** FK to auth-service users. The user casting this vote. */
  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  /** Vote type: HELPFUL or UNHELPFUL. */
  @Column(name = "vote_type", nullable = false, length = 32)
  private String voteType;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
