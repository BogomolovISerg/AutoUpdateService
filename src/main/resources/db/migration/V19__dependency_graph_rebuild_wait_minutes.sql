ALTER TABLE public.app_settings
    ADD COLUMN IF NOT EXISTS dependency_graph_rebuild_wait_minutes integer NOT NULL DEFAULT 60;

UPDATE public.app_settings
SET dependency_graph_rebuild_wait_minutes = 60
WHERE dependency_graph_rebuild_wait_minutes IS NULL
   OR dependency_graph_rebuild_wait_minutes <= 0;
