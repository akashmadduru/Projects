package com.acme.ecommerce.rating.service;

import com.acme.ecommerce.common.core.error.ForbiddenException;
import com.acme.ecommerce.common.core.error.ResourceConflictException;
import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.error.ValidationException;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.rating.api.dto.RatingMediaResponse;
import com.acme.ecommerce.rating.domain.Rating;
import com.acme.ecommerce.rating.domain.ReviewMedia;
import com.acme.ecommerce.rating.repository.RatingRepository;
import com.acme.ecommerce.rating.repository.ReviewMediaRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Review photos. Files are stored under a random UUID name (never the client's), the type is decided
 * from magic bytes (never the client's Content-Type), so there is no path traversal or type spoofing
 * surface.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReviewMediaService {

  public static final int MAX_PER_REVIEW = 4;
  public static final long MAX_BYTES = 5L * 1024 * 1024;

  private final ReviewMediaRepository media;
  private final RatingRepository ratings;
  private final TimeProvider timeProvider;

  @Value("${rating.media.dir:/data/media}")
  private String mediaDir;

  /** A loaded file ready to stream. */
  public record StoredFile(byte[] bytes, String contentType) {}

  @Transactional
  public RatingMediaResponse upload(UUID ratingId, MultipartFile file, UUID userId) {
    Rating rating =
        ratings.findById(ratingId).orElseThrow(() -> new ResourceNotFoundException("Rating", ratingId));
    if (!Objects.equals(rating.getUserId(), userId)) {
      throw new ForbiddenException("Only the review author may add photos");
    }
    if (file == null || file.isEmpty()) {
      throw ValidationException.forField("file", "is required");
    }
    if (file.getSize() > MAX_BYTES) {
      throw ValidationException.forField("file", "must be at most 5 MB");
    }
    if (media.countByRatingId(ratingId) >= MAX_PER_REVIEW) {
      throw new ResourceConflictException(
          "A review can have at most " + MAX_PER_REVIEW + " photos");
    }
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw ValidationException.forField("file", "could not be read");
    }
    String[] typeAndExt = sniff(bytes);
    if (typeAndExt == null) {
      throw ValidationException.forField("file", "must be a JPEG, PNG or WebP image");
    }
    String fileName = UUID.randomUUID() + "." + typeAndExt[1];
    try {
      Path dir = Path.of(mediaDir);
      Files.createDirectories(dir);
      Files.write(dir.resolve(fileName), bytes);
    } catch (IOException e) {
      log.error("Could not store review media: {}", e.toString());
      throw new IllegalStateException("Could not store file", e);
    }
    ReviewMedia saved =
        media.saveAndFlush(
            ReviewMedia.builder()
                .ratingId(ratingId)
                .fileName(fileName)
                .contentType(typeAndExt[0])
                .sizeBytes(bytes.length)
                .createdAt(timeProvider.now())
                .build());
    return toResponse(saved);
  }

  @Transactional
  public void delete(UUID ratingId, UUID mediaId, UUID userId, boolean isAdmin) {
    ReviewMedia m =
        media
            .findById(mediaId)
            .filter(x -> x.getRatingId().equals(ratingId))
            .orElseThrow(() -> new ResourceNotFoundException("Media", mediaId));
    Rating rating =
        ratings.findById(ratingId).orElseThrow(() -> new ResourceNotFoundException("Rating", ratingId));
    if (!isAdmin && !Objects.equals(rating.getUserId(), userId)) {
      throw new ForbiddenException("Only the review author may remove photos");
    }
    media.delete(m);
    deleteFile(m.getFileName());
  }

  public StoredFile load(UUID mediaId) {
    ReviewMedia m =
        media.findById(mediaId).orElseThrow(() -> new ResourceNotFoundException("Media", mediaId));
    try {
      return new StoredFile(Files.readAllBytes(Path.of(mediaDir).resolve(m.getFileName())), m.getContentType());
    } catch (IOException e) {
      throw new ResourceNotFoundException("Media", mediaId);
    }
  }

  /** Photos grouped by rating id, for list responses (one query). */
  public Map<UUID, List<RatingMediaResponse>> forRatings(Collection<UUID> ratingIds) {
    if (ratingIds.isEmpty()) {
      return Map.of();
    }
    return media.findByRatingIdInOrderByCreatedAtAsc(ratingIds).stream()
        .collect(
            Collectors.groupingBy(
                ReviewMedia::getRatingId,
                Collectors.mapping(ReviewMediaService::toResponse, Collectors.toList())));
  }

  /** Removes the files of a review that is being deleted (rows go via ON DELETE CASCADE). */
  public void deleteFilesOf(UUID ratingId) {
    media.findByRatingIdOrderByCreatedAtAsc(ratingId).forEach(m -> deleteFile(m.getFileName()));
  }

  private void deleteFile(String fileName) {
    try {
      Files.deleteIfExists(Path.of(mediaDir).resolve(fileName));
    } catch (IOException e) {
      log.warn("Could not delete media file {}: {}", fileName, e.toString());
    }
  }

  private static RatingMediaResponse toResponse(ReviewMedia m) {
    return new RatingMediaResponse(m.getId(), "/api/ratings/media/" + m.getId());
  }

  /** Returns {contentType, extension} from magic bytes, or null when not JPEG/PNG/WebP. */
  private static String[] sniff(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
      return new String[] {"image/jpeg", "jpg"};
    }
    if (b.length >= 8
        && (b[0] & 0xFF) == 0x89
        && b[1] == 'P'
        && b[2] == 'N'
        && b[3] == 'G'
        && b[4] == 0x0D
        && b[5] == 0x0A
        && b[6] == 0x1A
        && b[7] == 0x0A) {
      return new String[] {"image/png", "png"};
    }
    if (b.length >= 12
        && b[0] == 'R'
        && b[1] == 'I'
        && b[2] == 'F'
        && b[3] == 'F'
        && b[8] == 'W'
        && b[9] == 'E'
        && b[10] == 'B'
        && b[11] == 'P') {
      return new String[] {"image/webp", "webp"};
    }
    return null;
  }
}
