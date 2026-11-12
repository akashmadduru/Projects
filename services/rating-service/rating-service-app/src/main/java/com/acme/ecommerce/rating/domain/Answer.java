package com.acme.ecommerce.rating.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An answer to a {@link Question}; {@code verifiedBuyer} is decided once, at write time. */
@Entity
@Table(name = "answers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Answer {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "question_id", nullable = false, updatable = false)
  private UUID questionId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "author_name", length = 200)
  private String authorName;

  @Column(name = "text", nullable = false, length = 2000)
  private String text;

  @Column(name = "verified_buyer", nullable = false)
  private boolean verifiedBuyer;

  @Builder.Default
  @Column(name = "upvotes", nullable = false)
  private int upvotes = 0;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
