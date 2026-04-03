alter table if exists public.execution_run
    add column if not exists dependency_snapshot_id uuid;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'execution_run_dependency_snapshot_id_fkey'
    ) THEN
ALTER TABLE public.execution_run
    ADD CONSTRAINT execution_run_dependency_snapshot_id_fkey
        FOREIGN KEY (dependency_snapshot_id)
            REFERENCES public.dependency_snapshot(id)
            ON DELETE SET NULL;
END IF;
END $$;

create index if not exists ix_execution_run_dependency_snapshot_id
    on public.execution_run(dependency_snapshot_id);
