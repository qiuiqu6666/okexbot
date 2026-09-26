CREATE TABLE user_risk_policy (
  user_id BIGINT PRIMARY KEY, payload TEXT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE TABLE live_user_risk_policy (
  user_id BIGINT PRIMARY KEY, payload TEXT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE TABLE user_risk_state (
  user_id BIGINT PRIMARY KEY, payload MEDIUMTEXT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE TABLE live_user_risk_state (
  user_id BIGINT PRIMARY KEY, payload MEDIUMTEXT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE TABLE user_exchange_account (
  user_id BIGINT PRIMARY KEY, exchange_uid VARCHAR(64) NOT NULL UNIQUE,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE TABLE live_user_exchange_account (
  user_id BIGINT PRIMARY KEY, exchange_uid VARCHAR(64) NOT NULL UNIQUE,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
