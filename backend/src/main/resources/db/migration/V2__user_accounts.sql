-- V1 data stays untouched and unassigned. New registrations never inherit legacy trading accounts.
CREATE TABLE app_user (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(32) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  created_at VARCHAR(40) NOT NULL
);
CREATE TABLE auth_session (
  token_hash VARCHAR(64) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  expires_at BIGINT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE INDEX idx_session_user ON auth_session(user_id);
CREATE INDEX idx_session_expiry ON auth_session(expires_at);
CREATE TABLE user_bot_config (
  user_id BIGINT PRIMARY KEY,
  payload TEXT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE TABLE user_order_intent (
  client_id VARCHAR(32) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  instrument VARCHAR(40) NOT NULL,
  action VARCHAR(24) NOT NULL,
  state VARCHAR(24) NOT NULL,
  payload TEXT NOT NULL,
  exchange_id VARCHAR(64),
  detail TEXT,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE INDEX idx_user_order_state ON user_order_intent(user_id,state);
CREATE TABLE user_audit_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  kind VARCHAR(40) NOT NULL,
  summary VARCHAR(500) NOT NULL,
  payload TEXT NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE INDEX idx_user_audit ON user_audit_event(user_id,id);
CREATE TABLE user_equity_baseline (
  user_id BIGINT NOT NULL,
  day_utc VARCHAR(10) NOT NULL,
  equity DECIMAL(30,10) NOT NULL,
  PRIMARY KEY (user_id,day_utc),
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE TABLE user_execution_lease (
  user_id BIGINT PRIMARY KEY,
  owner VARCHAR(64) NOT NULL,
  expires_at BIGINT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE TABLE user_connection (
  user_id BIGINT PRIMARY KEY,
  ciphertext TEXT NOT NULL,
  okx_key_hash VARCHAR(64) UNIQUE,
  updated_at VARCHAR(40) NOT NULL,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
