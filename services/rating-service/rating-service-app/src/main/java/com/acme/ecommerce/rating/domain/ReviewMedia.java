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

/** Metadata of one review photo; the bytes live on disk under a random {@code fileName}. */
@Entity
@Table(name = "review_media")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ReviewMedia {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "rating_id", nullable = false, updatable = false)
  private UUID ratingId;

  @Column(name = "file_name", nullable = false, length = 80)
  private String fileName;

  @Column(name = "content_type", nullable = false, length = 40)
  private String contentType;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;
}
