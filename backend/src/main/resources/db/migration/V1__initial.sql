CREATE TABLE bot_config (
  id INT PRIMARY KEY,
  payload TEXT NOT NULL
);
CREATE TABLE order_intent (
  client_id VARCHAR(32) PRIMARY KEY,
  instrument VARCHAR(40) NOT NULL,
  action VARCHAR(24) NOT NULL,
  state VARCHAR(24) NOT NULL,
  payload TEXT NOT NULL,
  exchange_id VARCHAR(64),
  detail TEXT,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL
);
CREATE INDEX idx_order_state ON order_intent(state);
CREATE TABLE audit_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  kind VARCHAR(40) NOT NULL,
  summary VARCHAR(500) NOT NULL,
  payload TEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL
);
CREATE TABLE equity_baseline (
  day_utc VARCHAR(10) PRIMARY KEY,
  equity DECIMAL(30,10) NOT NULL
);
CREATE TABLE execution_lease (
  id INT PRIMARY KEY,
  owner VARCHAR(64) NOT NULL,
  expires_at BIGINT NOT NULL
);
INSERT INTO execution_lease (id, owner, expires_at) VALUES (1, '', 0);
