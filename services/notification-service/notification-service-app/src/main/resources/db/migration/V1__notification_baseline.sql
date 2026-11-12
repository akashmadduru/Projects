-- notification-service owns its data since Phase 8.
CREATE SCHEMA IF NOT EXISTS notification;

CREATE TABLE notification.notifications
(
    id         uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    user_id    uuid         NOT NULL,
    type       varchar(10)  NOT NULL CHECK (type IN ('ORDER', 'ALERT', 'PROMO', 'ACCOUNT', 'SYSTEM')),
    title      varchar(200) NOT NULL,
    body       varchar(1000),
    link       varchar(500),
    dedupe_key varchar(120) UNIQUE,
    read_at    timestamptz,
    created_at timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_notifications_user ON notification.notifications (user_id, created_at DESC);
CREATE INDEX ix_notifications_unread ON notification.notifications (user_id) WHERE read_at IS NULL;

CREATE TABLE notification.push_subscriptions
(
    id              uuid PRIMARY KEY       DEFAULT gen_random_uuid(),
    user_id         uuid          NOT NULL,
    endpoint        varchar(2048) NOT NULL,
    p256dh          varchar(200)  NOT NULL,
    auth            varchar(100)  NOT NULL,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    last_success_at timestamptz
);
CREATE UNIQUE INDEX ux_push_endpoint ON notification.push_subscriptions (md5(endpoint));
CREATE INDEX ix_push_user ON notification.push_subscriptions (user_id);

CREATE TABLE notification.settings
(
    key   varchar(80) PRIMARY KEY,
    value text NOT NULL
);

CREATE TABLE notification.campaigns
(
    id         uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    name       varchar(120) NOT NULL,
    channel    varchar(10)  NOT NULL CHECK (channel IN ('EMAIL', 'PUSH', 'INBOX', 'ALL')),
    audience   varchar(20)  NOT NULL CHECK (audience IN ('ALL', 'MEMBERS', 'INACTIVE_30D')),
    subject    varchar(200) NOT NULL,
    body       text         NOT NULL,
    link       varchar(500),
    status     varchar(10)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SENDING', 'SENT')),
    sent_count integer      NOT NULL DEFAULT 0,
    created_at timestamptz  NOT NULL DEFAULT now(),
    sent_at    timestamptz
);
