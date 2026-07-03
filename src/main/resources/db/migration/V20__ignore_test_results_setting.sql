ALTER TABLE public.app_settings
    ADD COLUMN IF NOT EXISTS ignore_test_results boolean NOT NULL DEFAULT false;

UPDATE public.app_settings
SET ignore_test_results = false
WHERE ignore_test_results IS NULL;
