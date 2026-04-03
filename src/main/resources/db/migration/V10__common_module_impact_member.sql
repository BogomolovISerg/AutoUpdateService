alter table if exists public.common_module_impact
    add column if not exists common_module_member_name varchar(255);

create index if not exists ix_common_module_impact_member
    on public.common_module_impact (common_module_name, common_module_member_name);
