CREATE TABLE IF NOT EXISTS public.dependency_graph_state (
                                                             id bigint NOT NULL,
                                                             active_snapshot_id uuid,
                                                             graph_is_stale boolean DEFAULT false NOT NULL,
                                                             stale_since timestamp with time zone,
                                                             stale_reason text,
                                                             last_git_change_at timestamp with time zone,
                                                             last_rebuild_at timestamp with time zone,
                                                             updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT dependency_graph_state_pkey PRIMARY KEY (id),
    CONSTRAINT dependency_graph_state_active_snapshot_id_fkey
    FOREIGN KEY (active_snapshot_id)
    REFERENCES public.dependency_snapshot(id)
    ON DELETE SET NULL
    );

INSERT INTO public.dependency_graph_state (id, graph_is_stale, updated_at)
VALUES (1, false, now())
    ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS public.dependency_graph_dirty_item (
                                                                  id uuid DEFAULT gen_random_uuid() NOT NULL,
    source_kind character varying(20) NOT NULL,
    source_name character varying(200) NOT NULL,
    module_name character varying(255) NOT NULL,
    changed_path character varying(2000) NOT NULL,
    detected_at timestamp with time zone DEFAULT now() NOT NULL,
    status character varying(20) NOT NULL,
    CONSTRAINT dependency_graph_dirty_item_pkey PRIMARY KEY (id)
    );

CREATE INDEX IF NOT EXISTS ix_dependency_graph_dirty_item_status
    ON public.dependency_graph_dirty_item USING btree (status);

CREATE INDEX IF NOT EXISTS ix_dependency_graph_dirty_item_detected_at
    ON public.dependency_graph_dirty_item USING btree (detected_at);

CREATE UNIQUE INDEX IF NOT EXISTS ux_dependency_graph_dirty_item_new_key
    ON public.dependency_graph_dirty_item USING btree (source_kind, source_name, module_name, changed_path, status);
