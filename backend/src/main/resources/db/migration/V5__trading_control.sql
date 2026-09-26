CREATE TABLE user_trading_control (
  user_id BIGINT PRIMARY KEY,
  enabled TINYINT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE TABLE live_user_trading_control (
  user_id BIGINT PRIMARY KEY,
  enabled TINYINT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
