CREATE TABLE IF NOT EXISTS public.code_source_root (
                                                       id uuid DEFAULT gen_random_uuid() NOT NULL,
    source_kind character varying(20) NOT NULL,
    source_name character varying(200) NOT NULL,
    root_path character varying(2000) NOT NULL,
    priority integer DEFAULT 0 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT code_source_root_pkey PRIMARY KEY (id)
    );

CREATE INDEX IF NOT EXISTS ix_code_source_root_kind
    ON public.code_source_root USING btree (source_kind);

CREATE INDEX IF NOT EXISTS ix_code_source_root_enabled
    ON public.code_source_root USING btree (enabled);

CREATE TABLE IF NOT EXISTS public.dependency_snapshot (
                                                          id uuid DEFAULT gen_random_uuid() NOT NULL,
    source_root_id uuid NOT NULL,
    status character varying(30) NOT NULL,
    build_mode character varying(20) NOT NULL,
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
                             files_scanned integer DEFAULT 0 NOT NULL,
                             notes text,
                             CONSTRAINT dependency_snapshot_pkey PRIMARY KEY (id),
    CONSTRAINT dependency_snapshot_source_root_id_fkey
    FOREIGN KEY (source_root_id)
    REFERENCES public.code_source_root(id)
                         ON DELETE CASCADE
    );

CREATE INDEX IF NOT EXISTS ix_dependency_snapshot_source_root
    ON public.dependency_snapshot USING btree (source_root_id);

CREATE INDEX IF NOT EXISTS ix_dependency_snapshot_status
    ON public.dependency_snapshot USING btree (status);

CREATE INDEX IF NOT EXISTS ix_dependency_snapshot_started_at
    ON public.dependency_snapshot USING btree (started_at);

CREATE TABLE IF NOT EXISTS public.dependency_edge (
                                                      id uuid DEFAULT gen_random_uuid() NOT NULL,
    snapshot_id uuid NOT NULL,
    caller_type character varying(50) NOT NULL,
    caller_name character varying(255) NOT NULL,
    caller_member character varying(255),
    callee_module character varying(255) NOT NULL,
    callee_member character varying(255),
    source_path character varying(2000) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT dependency_edge_pkey PRIMARY KEY (id),
    CONSTRAINT dependency_edge_snapshot_id_fkey
    FOREIGN KEY (snapshot_id)
    REFERENCES public.dependency_snapshot(id)
                         ON DELETE CASCADE
    );

CREATE INDEX IF NOT EXISTS ix_dependency_edge_snapshot
    ON public.dependency_edge USING btree (snapshot_id);

CREATE INDEX IF NOT EXISTS ix_dependency_edge_callee_module
    ON public.dependency_edge USING btree (callee_module);

CREATE INDEX IF NOT EXISTS ix_dependency_edge_caller
    ON public.dependency_edge USING btree (caller_type, caller_name);

CREATE TABLE IF NOT EXISTS public.common_module_impact (
                                                           id uuid DEFAULT gen_random_uuid() NOT NULL,
    snapshot_id uuid NOT NULL,
    common_module_name character varying(255) NOT NULL,
    object_type character varying(50) NOT NULL,
    object_name character varying(255) NOT NULL,
    source_path character varying(2000),
    via_module character varying(255),
    via_member character varying(255),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT common_module_impact_pkey PRIMARY KEY (id),
    CONSTRAINT common_module_impact_snapshot_id_fkey
    FOREIGN KEY (snapshot_id)
    REFERENCES public.dependency_snapshot(id)
                         ON DELETE CASCADE
    );

CREATE INDEX IF NOT EXISTS ix_common_module_impact_snapshot
    ON public.common_module_impact USING btree (snapshot_id);

CREATE INDEX IF NOT EXISTS ix_common_module_impact_module
    ON public.common_module_impact USING btree (common_module_name);

CREATE INDEX IF NOT EXISTS ix_common_module_impact_object
    ON public.common_module_impact USING btree (object_type, object_name);
