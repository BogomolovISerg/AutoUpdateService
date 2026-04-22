CREATE INDEX IF NOT EXISTS ix_common_module_impact_tree_modules
    ON public.common_module_impact (snapshot_id, common_module_name, source_kind, source_name);

CREATE INDEX IF NOT EXISTS ix_common_module_impact_tree_methods
    ON public.common_module_impact (snapshot_id, source_kind, source_name, common_module_name, common_module_member_name);

CREATE INDEX IF NOT EXISTS ix_common_module_impact_tree_objects
    ON public.common_module_impact (snapshot_id, source_kind, source_name, common_module_name, common_module_member_name, object_type, object_name);

CREATE INDEX IF NOT EXISTS ix_common_module_impact_tree_module_lower
    ON public.common_module_impact (snapshot_id, lower(common_module_name));
