alter table if exists public.common_module_impact
    add column if not exists source_kind varchar(20) default 'BASE';

alter table if exists public.common_module_impact
    add column if not exists source_name varchar(200) default 'Основная конфигурация';

alter table if exists public.common_module_impact
alter column source_kind set default 'BASE';

alter table if exists public.common_module_impact
alter column source_name set default 'Основная конфигурация';
