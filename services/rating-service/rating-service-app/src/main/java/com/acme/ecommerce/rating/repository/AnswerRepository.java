package com.acme.ecommerce.rating.repository;

import com.acme.ecommerce.rating.domain.Answer;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AnswerRepository extends JpaRepository<Answer, UUID> {

  Page<Answer> findByQuestionId(UUID questionId, Pageable pageable);

  List<Answer> findTop3ByQuestionIdOrderByUpvotesDescCreatedAtAsc(UUID questionId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE Answer a SET a.upvotes = a.upvotes + 1 WHERE a.id = :id")
  int incrementUpvotes(@Param("id") UUID id);
}
