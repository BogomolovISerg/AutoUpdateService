create table if not exists step_log_blob (
                                             id uuid primary key default gen_random_uuid(),
    event_id uuid not null references log_event(id) on delete cascade,
    run_id uuid not null,
    step_code varchar(200) not null,
    kind varchar(20) not null,
    content text not null,
    created_at timestamptz not null default now()
    );

create index if not exists ix_step_log_blob_event on step_log_blob(event_id);
create index if not exists ix_step_log_blob_run_step on step_log_blob(run_id, step_code);
