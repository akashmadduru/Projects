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

/** A customer question on a product. Counters are maintained by bulk updates in the repository. */
@Entity
@Table(name = "questions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Question {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID productId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "author_name", length = 200)
  private String authorName;

  @Column(name = "text", nullable = false, length = 1000)
  private String text;

  @Builder.Default
  @Column(name = "answer_count", nullable = false)
  private int answerCount = 0;

  @Builder.Default
  @Column(name = "upvotes", nullable = false)
  private int upvotes = 0;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
