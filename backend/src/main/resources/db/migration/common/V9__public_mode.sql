-- =====================================================================================================
-- V9 (public mode, §16.7): the league is a public experiment. Every player's portfolio, orders, fills,
-- rationales, activity and journal are visible to every player, live. Data only; portable (PostgreSQL 18.6,
-- H2 2.4). On a fresh database app_setting and player are still empty here (SettingsService seeds the new
-- defaults at boot), so these statements touch nothing.
-- Note: social.showDepositsInFeed is forced to 'true', overriding an admin's explicit 'false'.
-- =====================================================================================================

-- The feed / trade list / journal delay goes to zero (the setting stays; the admin can still raise it).
UPDATE app_setting SET setting_value = 'PT0S', updated_by = NULL, updated_at = CURRENT_TIMESTAMP
 WHERE setting_key = 'social.tradeFeedDelay';

-- Deposits show in the feed.
UPDATE app_setting SET setting_value = 'true', updated_by = NULL, updated_at = CURRENT_TIMESTAMP
 WHERE setting_key = 'social.showDepositsInFeed';

-- social.holdingsVisibility is retired: holdings and equity are always public.
DELETE FROM app_setting WHERE setting_key = 'social.holdingsVisibility';

-- Rows written with the old delay become visible now (FILL, POSITION_CLOSED and JOURNAL rows; every other kind
-- already has visible_at = created_at).
UPDATE feed_event SET visible_at = created_at WHERE visible_at > created_at;
UPDATE journal_note SET visible_at = created_at WHERE visible_at > created_at;

-- One notice per non-disabled human (D13). The id reuses the player's ULID, which is unique per row.
INSERT INTO notification (public_id, player_id, kind, title, body, payload, source_ref, created_at)
SELECT 'ntf_' || SUBSTRING(p.public_id FROM 5), p.id, 'SYSTEM', 'The league is now public',
       'Every player''s portfolio, orders, fills, account activity and new notes are now visible to everyone, live. '
         || 'Notes you wrote before today keep the visibility you chose.',
       NULL, 'PUBLIC_MODE', CURRENT_TIMESTAMP
  FROM player p
 WHERE p.kind = 'HUMAN' AND p.status <> 'DISABLED';
