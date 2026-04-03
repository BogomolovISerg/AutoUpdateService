create extension if not exists pgcrypto;

create table if not exists repo_binding (
                                            id uuid primary key default gen_random_uuid(),
    project_path varchar(300) not null,
    target_type varchar(30) not null,
    extension_name varchar(200),
    repo_path varchar(1000) not null,
    active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );
create unique index if not exists uk_repo_binding_project_path on repo_binding(project_path);

create table if not exists update_task (
                                           id uuid primary key default gen_random_uuid(),
    repo_binding_id uuid not null references repo_binding(id),
    target_type varchar(30) not null,
    extension_name varchar(200),
    repo_path varchar(1000) not null,
    project_path varchar(300) not null,
    branch varchar(200),
    commit_sha varchar(80),
    author_name varchar(200),
    author_login varchar(200),
    comment varchar(4000),
    source_key varchar(700) not null,
    status varchar(30) not null,
    scheduled_for date not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );
create unique index if not exists uk_update_task_source_key on update_task(source_key);
create index if not exists ix_update_task_status_scheduled_for on update_task(status, scheduled_for);

create table if not exists app_settings (
                                            id bigint primary key,
                                            auto_update_enabled boolean not null default true,
                                            run_time time not null default '02:00',
                                            next_run_date date,
                                            timezone varchar(60) not null default 'Europe/Zurich',
    lock_message varchar(500) not null default 'Выполняется ночное обновление базы. Попробуйте позже.',
    uccode varchar(100) not null default 'AUTO_UPDATE_1C',
    closed_max_attempts int not null default 12,
    closed_sleep_seconds int not null default 15,
    updated_at timestamptz not null default now()
    );
insert into app_settings(id) values (1) on conflict (id) do nothing;

create table if not exists execution_run (
                                             id uuid primary key default gen_random_uuid(),
    planned_for timestamptz not null,
    started_at timestamptz,
    finished_at timestamptz,
    status varchar(30) not null,
    error_summary varchar(4000)
    );
create index if not exists ix_execution_run_planned_for on execution_run(planned_for);

create table if not exists log_event (
                                         id uuid primary key default gen_random_uuid(),
    ts timestamptz not null default now(),
    type varchar(50) not null,
    level varchar(20) not null,
    message varchar(4000) not null,
    data jsonb,
    client_ip varchar(80),
    actor varchar(200),
    run_id uuid
    );
create index if not exists ix_log_event_ts on log_event(ts);
create index if not exists ix_log_event_type_ts on log_event(type, ts);

create table if not exists app_user (
                                        id uuid primary key default gen_random_uuid(),
    username varchar(200) not null,
    password_hash varchar(200) not null,
    role varchar(50) not null default 'ADMIN',
    must_change_password boolean not null default true,
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
    );
create unique index if not exists uk_app_user_username on app_user(username);
