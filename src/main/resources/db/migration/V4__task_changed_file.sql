ALTER TABLE public.update_task
    ADD COLUMN IF NOT EXISTS before_sha character varying(80);

CREATE TABLE IF NOT EXISTS public.task_changed_file (
                                                        id uuid DEFAULT gen_random_uuid() NOT NULL,
    task_id uuid NOT NULL,
    run_id uuid NOT NULL,
    project_path character varying(300) NOT NULL,
    from_sha character varying(80),
    to_sha character varying(80),
    change_type character varying(30) NOT NULL,
    old_path character varying(2000),
    new_path character varying(2000),
    created_at timestamp with time zone DEFAULT now() NOT NULL
    );

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'task_changed_file_pkey'
    ) THEN
ALTER TABLE ONLY public.task_changed_file
    ADD CONSTRAINT task_changed_file_pkey PRIMARY KEY (id);
END IF;
END
$$;

CREATE INDEX IF NOT EXISTS ix_task_changed_file_run
    ON public.task_changed_file USING btree (run_id);

CREATE INDEX IF NOT EXISTS ix_task_changed_file_task
    ON public.task_changed_file USING btree (task_id);

CREATE INDEX IF NOT EXISTS ix_task_changed_file_task_run
    ON public.task_changed_file USING btree (task_id, run_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'task_changed_file_task_id_fkey'
    ) THEN
ALTER TABLE ONLY public.task_changed_file
    ADD CONSTRAINT task_changed_file_task_id_fkey
    FOREIGN KEY (task_id)
    REFERENCES public.update_task(id)
    ON DELETE CASCADE;
END IF;
END
$$;