create table if not exists public.dependency_scan_log (
                                                          id uuid default gen_random_uuid() not null,
    snapshot_id uuid not null,
    level varchar(10) not null,
    phase varchar(50) not null,
    source_path varchar(2000),
    message text not null,
    stacktrace text,
    created_at timestamptz not null default now(),
    constraint dependency_scan_log_pkey primary key (id),
    constraint fk_dependency_scan_log_snapshot
    foreign key (snapshot_id)
    references public.dependency_snapshot(id)
    on delete cascade
    );

create index if not exists ix_dependency_scan_log_snapshot_created_at
    on public.dependency_scan_log (snapshot_id, created_at desc);

create index if not exists ix_dependency_scan_log_snapshot_level
    on public.dependency_scan_log (snapshot_id, level);

create index if not exists ix_dependency_scan_log_snapshot_phase
    on public.dependency_scan_log (snapshot_id, phase);