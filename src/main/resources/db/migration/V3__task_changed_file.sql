alter table update_task
    add column if not exists before_sha varchar(80);

create table if not exists task_changed_file (
                                                 id uuid primary key default gen_random_uuid(),
    task_id uuid not null references update_task(id) on delete cascade,
    run_id uuid not null,
    project_path varchar(300) not null,
    from_sha varchar(80),
    to_sha varchar(80),
    change_type varchar(30) not null,
    old_path varchar(2000),
    new_path varchar(2000),
    created_at timestamptz not null default now()
    );

create index if not exists ix_task_changed_file_task on task_changed_file(task_id);
create index if not exists ix_task_changed_file_run on task_changed_file(run_id);
create index if not exists ix_task_changed_file_task_run on task_changed_file(task_id, run_id);
