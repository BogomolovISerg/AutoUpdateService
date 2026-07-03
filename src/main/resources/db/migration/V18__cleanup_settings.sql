ALTER TABLE public.app_settings
    ADD COLUMN IF NOT EXISTS cleanup_enabled boolean,
    ADD COLUMN IF NOT EXISTS cleanup_run_time time,
    ADD COLUMN IF NOT EXISTS next_cleanup_run_date date,
    ADD COLUMN IF NOT EXISTS cleanup_keep_days integer;

UPDATE public.app_settings
SET cleanup_enabled = COALESCE(cleanup_enabled, true),
    cleanup_run_time = COALESCE(cleanup_run_time, time '01:00'),
    cleanup_keep_days = COALESCE(cleanup_keep_days, 30);

ALTER TABLE public.app_settings
    ALTER COLUMN cleanup_enabled SET NOT NULL;

ALTER TABLE public.app_settings
    ALTER COLUMN cleanup_run_time SET NOT NULL;

ALTER TABLE public.app_settings
    ALTER COLUMN cleanup_keep_days SET NOT NULL;
