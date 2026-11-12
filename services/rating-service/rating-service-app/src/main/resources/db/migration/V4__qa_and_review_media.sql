-- Phase 7: product Q&A (questions, answers, upvotes) and review photos.

CREATE TABLE rating.questions
(
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id   uuid          NOT NULL,
    user_id      uuid          NOT NULL,
    author_name  varchar(200),
    text         varchar(1000) NOT NULL,
    answer_count integer       NOT NULL DEFAULT 0,
    upvotes      integer       NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL
);
CREATE INDEX questions_product_idx ON rating.questions (product_id, created_at DESC);

CREATE TABLE rating.answers
(
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id    uuid          NOT NULL REFERENCES rating.questions (id) ON DELETE CASCADE,
    user_id        uuid          NOT NULL,
    author_name    varchar(200),
    text           varchar(2000) NOT NULL,
    verified_buyer boolean       NOT NULL DEFAULT false,
    upvotes        integer       NOT NULL DEFAULT 0,
    created_at     timestamptz   NOT NULL
);
CREATE INDEX answers_question_idx ON rating.answers (question_id, upvotes DESC, created_at);

-- One upvote per user per item. item_type is QUESTION or ANSWER.
CREATE TABLE rating.qa_votes
(
    item_type  varchar(16) NOT NULL,
    item_id    uuid        NOT NULL,
    user_id    uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (item_type, item_id, user_id)
);

CREATE TABLE rating.review_media
(
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    rating_id    uuid         NOT NULL REFERENCES rating.ratings (id) ON DELETE CASCADE,
    file_name    varchar(80)  NOT NULL,
    content_type varchar(40)  NOT NULL,
    size_bytes   bigint       NOT NULL,
    created_at   timestamptz  NOT NULL
);
CREATE INDEX review_media_rating_idx ON rating.review_media (rating_id);
