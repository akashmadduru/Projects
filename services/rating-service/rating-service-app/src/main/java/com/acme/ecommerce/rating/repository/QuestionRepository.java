package com.acme.ecommerce.rating.repository;

import com.acme.ecommerce.rating.domain.Question;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface QuestionRepository extends JpaRepository<Question, UUID> {

  Page<Question> findByProductId(UUID productId, Pageable pageable);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE Question q SET q.answerCount = q.answerCount + 1 WHERE q.id = :id")
  int incrementAnswerCount(@Param("id") UUID id);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE Question q SET q.upvotes = q.upvotes + 1 WHERE q.id = :id")
  int incrementUpvotes(@Param("id") UUID id);

  /**
   * Idempotent upvote ledger insert (no entity): returns 1 when the vote is new and 0 when this
   * user already voted, so the caller bumps the counter exactly once.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          "INSERT INTO rating.qa_votes (item_type, item_id, user_id)"
              + " VALUES (:type, :itemId, :userId) ON CONFLICT DO NOTHING",
      nativeQuery = true)
  int insertVoteIfAbsent(
      @Param("type") String type, @Param("itemId") UUID itemId, @Param("userId") UUID userId);
}
