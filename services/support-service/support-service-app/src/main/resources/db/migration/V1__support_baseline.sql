CREATE TABLE IF NOT EXISTS faq_articles (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    slug          varchar(160) NOT NULL UNIQUE,
    category      varchar(20)  NOT NULL,
    title         varchar(200) NOT NULL,
    body          text         NOT NULL,
    keywords      text[]       NOT NULL DEFAULT '{}',
    published     boolean      NOT NULL DEFAULT true,
    helpful_yes   integer      NOT NULL DEFAULT 0,
    helpful_no    integer      NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT chk_faq_category CHECK (category IN ('ORDERS','PAYMENTS','RETURNS','ACCOUNT','SELLERS','OTHER'))
);
CREATE INDEX IF NOT EXISTS idx_faq_category ON faq_articles (category) WHERE published;

CREATE TABLE IF NOT EXISTS tickets (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               uuid         NOT NULL,
    email                 varchar(320) NOT NULL,
    order_id              uuid,
    shipment_id           uuid,
    category              varchar(20)  NOT NULL,
    subject               varchar(200) NOT NULL,
    status                varchar(20)  NOT NULL DEFAULT 'OPEN',
    priority              varchar(10)  NOT NULL DEFAULT 'NORMAL',
    assignee_user_id      uuid,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    first_response_at     timestamptz,
    resolved_at           timestamptz,
    first_response_due_at timestamptz  NOT NULL,
    resolution_due_at     timestamptz  NOT NULL,
    csat                  smallint,
    CONSTRAINT chk_ticket_status CHECK (status IN ('OPEN','PENDING_CUSTOMER','PENDING_AGENT','RESOLVED','CLOSED')),
    CONSTRAINT chk_ticket_priority CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT')),
    CONSTRAINT chk_ticket_csat CHECK (csat IS NULL OR csat BETWEEN 1 AND 5)
);
CREATE INDEX IF NOT EXISTS idx_tickets_user ON tickets (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_tickets_queue ON tickets (status, priority, created_at);

CREATE TABLE IF NOT EXISTS ticket_messages (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    ticket_id   uuid        NOT NULL REFERENCES tickets (id) ON DELETE CASCADE,
    author_type varchar(10) NOT NULL,
    author_id   uuid,
    body        varchar(5000) NOT NULL,
    internal    boolean     NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_msg_author CHECK (author_type IN ('CUSTOMER','AGENT','BOT','SYSTEM'))
);
CREATE INDEX IF NOT EXISTS idx_msgs_ticket ON ticket_messages (ticket_id, created_at);

CREATE TABLE IF NOT EXISTS chat_sessions (
    session_id  varchar(64) PRIMARY KEY,
    user_id     uuid,
    low_conf    integer     NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS chat_messages (
    id          bigserial PRIMARY KEY,
    session_id  varchar(64) NOT NULL REFERENCES chat_sessions (session_id) ON DELETE CASCADE,
    role        varchar(4)  NOT NULL,
    text        varchar(2000) NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_chat_msgs ON chat_messages (session_id, id);

CREATE TABLE IF NOT EXISTS audit_log (
    id             uuid PRIMARY KEY,
    actor_user_id  uuid,
    actor_roles    varchar(200),
    action         varchar(80)  NOT NULL,
    resource_type  varchar(80),
    resource_id    varchar(80),
    before_json    text,
    after_json     text,
    ip             varchar(64),
    at             timestamptz  NOT NULL,
    received_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_audit_at ON audit_log (at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_actor ON audit_log (actor_user_id, at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_resource ON audit_log (resource_type, resource_id);
