ALTER TABLE public.app_settings
    ADD COLUMN IF NOT EXISTS dependency_graph_rebuild_enabled boolean NOT NULL DEFAULT true;
