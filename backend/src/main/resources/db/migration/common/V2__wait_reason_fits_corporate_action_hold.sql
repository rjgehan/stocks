-- V2 (WS3, §8 "Migrations after V1", §11.5, §14): trade_order.wait_reason was VARCHAR(20), one character short of
-- CORPORATE_ACTION_HOLD (21). Production already applied V1_4_01 and runs flyway.out-of-order=false, so this is V2,
-- not a V1_3_* lane file (that would sort before V1_4_01 and fail validation at boot).
-- SET DATA TYPE is the spelling both H2 2.4 and PostgreSQL 18 accept.
ALTER TABLE trade_order ALTER COLUMN wait_reason SET DATA TYPE VARCHAR(24);
