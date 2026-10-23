-- V14 (§30, the PostgreSQL half; the H2 half is h2/V14__options.sql): long US equity options. V1 declared the
-- execution CHECKs inline (PostgreSQL names them <table>_<column>_check); drop whichever CHECK mentions the column, so
-- the step does not depend on the generated name, then add the named replacement.
DO $$
DECLARE c RECORD;
BEGIN
  FOR c IN SELECT conrelid::regclass::text AS tbl, conname FROM pg_constraint
            WHERE contype = 'c' AND conrelid = 'execution'::regclass AND (
                  pg_get_constraintdef(oid) LIKE '%(price > %'
               OR pg_get_constraintdef(oid) LIKE '%liquidity%')
  LOOP
    EXECUTE 'ALTER TABLE ' || quote_ident(c.tbl) || ' DROP CONSTRAINT ' || quote_ident(c.conname);
  END LOOP;
END $$;
-- 1. OCC contract symbols (AAPL261120C00250000) are up to 21 characters: every symbol column becomes VARCHAR(24).
ALTER TABLE asset                ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE daily_mark           ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE price_adjustment     ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE corporate_action     ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE corporate_action     ALTER COLUMN new_symbol    TYPE VARCHAR(24);
ALTER TABLE trade_order          ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE execution            ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE dividend_entitlement ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE cash_ledger          ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE position             ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE position_lot         ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE position_adjustment  ALTER COLUMN symbol_before TYPE VARCHAR(24);
ALTER TABLE position_adjustment  ALTER COLUMN symbol_after  TYPE VARCHAR(24);
ALTER TABLE account_event        ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE account_violation    ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE snapshot_holding     ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE benchmark_daily      ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE feed_event           ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE watchlist_item       ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE order_group          ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE price_alert          ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE screener_stat        ALTER COLUMN symbol        TYPE VARCHAR(24);
ALTER TABLE journal_note         ALTER COLUMN symbols       TYPE VARCHAR(200);
-- 2. asset.asset_type gains OPTION (one row per contract anyone ordered).
ALTER TABLE asset DROP CONSTRAINT ck_asset_type;
ALTER TABLE asset ADD CONSTRAINT ck_asset_type
  CHECK (asset_type IN ('COMMON_STOCK','ETF','ADR','OTHER','UNKNOWN','CRYPTO','OPTION'));
-- 3. An option that expires worthless is settled by a SELL execution at 0.00 with liquidity EXPIRY.
ALTER TABLE execution ADD CONSTRAINT ck_execution_price CHECK (price > 0 OR (price = 0 AND liquidity = 'EXPIRY'));
ALTER TABLE execution ADD CONSTRAINT ck_execution_liquidity
  CHECK (liquidity IN ('TOUCH','WALK','RESTING','PRINT','OPEN','EXPIRY'));
-- 4. Option day-end marks are the closing quote's mid.
ALTER TABLE daily_mark DROP CONSTRAINT ck_daily_mark_basis;
ALTER TABLE daily_mark ADD CONSTRAINT ck_daily_mark_basis
  CHECK (day_end_basis IN ('SIP_1MIN','OFFICIAL_CLOSE','LAST_TRADE','SIMULATED','CRYPTO_1MIN','OPTION_MID'));
