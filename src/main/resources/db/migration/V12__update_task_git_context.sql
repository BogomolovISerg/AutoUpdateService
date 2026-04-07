ALTER TABLE public.update_task
    ADD COLUMN IF NOT EXISTS git_project_id bigint,
    ADD COLUMN IF NOT EXISTS git_project_name character varying(300),
    ADD COLUMN IF NOT EXISTS git_ref character varying(300),
    ADD COLUMN IF NOT EXISTS git_checkout_sha character varying(80),
    ADD COLUMN IF NOT EXISTS git_event_name character varying(100),
    ADD COLUMN IF NOT EXISTS git_object_kind character varying(100),
    ADD COLUMN IF NOT EXISTS git_user_name character varying(200),
    ADD COLUMN IF NOT EXISTS git_user_username character varying(200),
    ADD COLUMN IF NOT EXISTS git_user_email character varying(500),
    ADD COLUMN IF NOT EXISTS git_total_commits_count integer,
    ADD COLUMN IF NOT EXISTS git_webhook_received_at timestamp with time zone;
