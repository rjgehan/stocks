-- WS3 (§11.5, §14): trade_order.wait_reason was VARCHAR(20), one character short of CORPORATE_ACTION_HOLD (21).
-- SET DATA TYPE is the spelling both H2 2.4 and PostgreSQL 18 accept.
ALTER TABLE trade_order ALTER COLUMN wait_reason SET DATA TYPE VARCHAR(24);
