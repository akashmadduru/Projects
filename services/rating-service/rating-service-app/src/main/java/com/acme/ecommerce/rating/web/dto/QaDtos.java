package com.acme.ecommerce.rating.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response shapes for product Q&A. */
public final class QaDtos {

  private QaDtos() {}

  public record QuestionRequest(@NotBlank @Size(max = 1000) String text) {}

  public record AnswerRequest(@NotBlank @Size(max = 2000) String text) {}

  public record AnswerResponse(
      UUID id,
      UUID questionId,
      String text,
      String authorName,
      boolean verifiedBuyer,
      int upvotes,
      Instant createdAt) {}

  public record QuestionResponse(
      UUID id,
      UUID productId,
      String text,
      String authorName,
      Instant createdAt,
      int upvotes,
      int answerCount,
      List<AnswerResponse> answers) {}

  public record UpvoteResponse(UUID id, int upvotes) {}
}
