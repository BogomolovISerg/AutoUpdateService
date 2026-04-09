ALTER TABLE public.execution_run
    ADD COLUMN IF NOT EXISTS stage character varying(20);

UPDATE public.execution_run
SET stage = 'PRODUCTION'
WHERE stage IS NULL;

ALTER TABLE public.execution_run
    ALTER COLUMN stage SET NOT NULL;

ALTER TABLE public.app_settings
    ADD COLUMN IF NOT EXISTS test_run_time time,
    ADD COLUMN IF NOT EXISTS next_test_run_date date,
    ADD COLUMN IF NOT EXISTS production_run_time time,
    ADD COLUMN IF NOT EXISTS next_production_run_date date;

UPDATE public.app_settings
SET test_run_time = COALESCE(test_run_time, run_time, time '02:00'),
    next_test_run_date = COALESCE(next_test_run_date, next_run_date),
    production_run_time = COALESCE(production_run_time, time '04:00'),
    next_production_run_date = COALESCE(next_production_run_date, next_run_date);

ALTER TABLE public.app_settings
    ALTER COLUMN test_run_time SET NOT NULL;

ALTER TABLE public.app_settings
    ALTER COLUMN production_run_time SET NOT NULL;
