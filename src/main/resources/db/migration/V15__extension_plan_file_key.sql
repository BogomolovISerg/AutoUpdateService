alter table if exists repo_binding
    add column if not exists extension_plan_file_key varchar(200);

alter table if exists update_task
    add column if not exists extension_plan_file_key varchar(200);

update repo_binding
set extension_plan_file_key = substring(
        regexp_replace(trim(extension_name), '[^0-9A-Za-zА-Яа-я._-]+', '_', 'g')
        from 1 for 60
                              )
where target_type = 'EXTENSION'
  and extension_name is not null
  and btrim(extension_name) <> ''
  and (extension_plan_file_key is null or btrim(extension_plan_file_key) = '');

update update_task t
set extension_plan_file_key = coalesce(
        nullif(btrim(rb.extension_plan_file_key), ''),
        substring(
                regexp_replace(trim(t.extension_name), '[^0-9A-Za-zА-Яа-я._-]+', '_', 'g')
                from 1 for 60
        )
                              )
    from repo_binding rb
where t.repo_binding_id = rb.id
  and (t.extension_plan_file_key is null or btrim(t.extension_plan_file_key) = '');
