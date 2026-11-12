package com.acme.ecommerce.rating.service;

import com.acme.ecommerce.common.core.error.ResourceNotFoundException;
import com.acme.ecommerce.common.core.time.TimeProvider;
import com.acme.ecommerce.rating.domain.Answer;
import com.acme.ecommerce.rating.domain.Question;
import com.acme.ecommerce.rating.repository.AnswerRepository;
import com.acme.ecommerce.rating.repository.QuestionRepository;
import com.acme.ecommerce.rating.web.dto.QaDtos.AnswerResponse;
import com.acme.ecommerce.rating.web.dto.QaDtos.QuestionResponse;
import com.acme.ecommerce.rating.web.dto.QaDtos.UpvoteResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Product Q&A persistence. Remote checks (display name, verified buyer) happen in the controller. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class QaService {

  private final QuestionRepository questions;
  private final AnswerRepository answers;
  private final TimeProvider timeProvider;

  public Page<QuestionResponse> listQuestions(UUID productId, Pageable pageable) {
    return questions
        .findByProductId(productId, pageable)
        .map(
            q ->
                toResponse(
                    q,
                    answers.findTop3ByQuestionIdOrderByUpvotesDescCreatedAtAsc(q.getId()).stream()
                        .map(QaService::toResponse)
                        .toList()));
  }

  public Page<AnswerResponse> listAnswers(UUID questionId, Pageable pageable) {
    load(questionId);
    return answers.findByQuestionId(questionId, pageable).map(QaService::toResponse);
  }

  public UUID productIdOfQuestion(UUID questionId) {
    return load(questionId).getProductId();
  }

  @Transactional
  public QuestionResponse ask(UUID productId, String text, UUID userId, String authorName) {
    Question q =
        questions.saveAndFlush(
            Question.builder()
                .productId(productId)
                .userId(userId)
                .authorName(authorName)
                .text(text.trim())
                .createdAt(timeProvider.now())
                .build());
    return toResponse(q, java.util.List.of());
  }

  @Transactional
  public AnswerResponse answer(
      UUID questionId, String text, UUID userId, String authorName, boolean verifiedBuyer) {
    load(questionId);
    Answer a =
        answers.saveAndFlush(
            Answer.builder()
                .questionId(questionId)
                .userId(userId)
                .authorName(authorName)
                .text(text.trim())
                .verifiedBuyer(verifiedBuyer)
                .createdAt(timeProvider.now())
                .build());
    questions.incrementAnswerCount(questionId);
    return toResponse(a);
  }

  /** Idempotent: a second upvote by the same user is a no-op returning the current count. */
  @Transactional
  public UpvoteResponse upvoteQuestion(UUID questionId, UUID userId) {
    load(questionId);
    if (questions.insertVoteIfAbsent("QUESTION", questionId, userId) == 1) {
      questions.incrementUpvotes(questionId);
    }
    return new UpvoteResponse(questionId, load(questionId).getUpvotes());
  }

  @Transactional
  public UpvoteResponse upvoteAnswer(UUID answerId, UUID userId) {
    answers.findById(answerId).orElseThrow(() -> new ResourceNotFoundException("Answer", answerId));
    if (questions.insertVoteIfAbsent("ANSWER", answerId, userId) == 1) {
      answers.incrementUpvotes(answerId);
    }
    return new UpvoteResponse(
        answerId, answers.findById(answerId).map(Answer::getUpvotes).orElse(0));
  }

  private Question load(UUID id) {
    return questions.findById(id).orElseThrow(() -> new ResourceNotFoundException("Question", id));
  }

  private static QuestionResponse toResponse(Question q, java.util.List<AnswerResponse> top) {
    return new QuestionResponse(
        q.getId(),
        q.getProductId(),
        q.getText(),
        q.getAuthorName(),
        q.getCreatedAt(),
        q.getUpvotes(),
        q.getAnswerCount(),
        top);
  }

  private static AnswerResponse toResponse(Answer a) {
    return new AnswerResponse(
        a.getId(),
        a.getQuestionId(),
        a.getText(),
        a.getAuthorName(),
        a.isVerifiedBuyer(),
        a.getUpvotes(),
        a.getCreatedAt());
  }
}
