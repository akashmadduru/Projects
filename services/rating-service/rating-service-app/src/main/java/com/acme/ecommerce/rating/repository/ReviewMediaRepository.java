package com.acme.ecommerce.rating.repository;

import com.acme.ecommerce.rating.domain.ReviewMedia;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewMediaRepository extends JpaRepository<ReviewMedia, UUID> {

  List<ReviewMedia> findByRatingIdOrderByCreatedAtAsc(UUID ratingId);

  List<ReviewMedia> findByRatingIdInOrderByCreatedAtAsc(Collection<UUID> ratingIds);

  long countByRatingId(UUID ratingId);
}
