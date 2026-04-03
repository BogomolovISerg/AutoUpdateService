alter table app_settings
    add column if not exists queue_page_size int not null default 50;

alter table app_settings
    add column if not exists logs_page_size int not null default 50;
