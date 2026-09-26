CREATE TABLE telegram_subscriber (
  chat_id BIGINT PRIMARY KEY,
  label VARCHAR(80) NOT NULL,
  created_at VARCHAR(40) NOT NULL
);
