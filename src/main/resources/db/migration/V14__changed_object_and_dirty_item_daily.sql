CREATE TABLE IF NOT EXISTS public.changed_object (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    business_date date NOT NULL,
    object_type character varying(50) NOT NULL,
    object_name character varying(255) NOT NULL,
    project_path character varying(300),
    changed_path character varying(2000),
    direct_change_detected boolean DEFAULT false NOT NULL,
    graph_impact_detected boolean DEFAULT false NOT NULL,
    first_detected_at timestamp with time zone DEFAULT now() NOT NULL,
    last_detected_at timestamp with time zone DEFAULT now() NOT NULL,
    status character varying(20) NOT NULL,
    CONSTRAINT changed_object_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS ix_changed_object_status
    ON public.changed_object USING btree (status);

CREATE INDEX IF NOT EXISTS ix_changed_object_business_date
    ON public.changed_object USING btree (business_date);

CREATE UNIQUE INDEX IF NOT EXISTS ux_changed_object_business_key
    ON public.changed_object USING btree (business_date, object_type, object_name);

ALTER TABLE public.dependency_graph_dirty_item
    ADD COLUMN IF NOT EXISTS business_date date,
    ADD COLUMN IF NOT EXISTS last_detected_at timestamp with time zone;

UPDATE public.dependency_graph_dirty_item
SET business_date = COALESCE(business_date, detected_at::date, CURRENT_DATE),
    last_detected_at = COALESCE(last_detected_at, detected_at, now());

ALTER TABLE public.dependency_graph_dirty_item
    ALTER COLUMN business_date SET NOT NULL;

ALTER TABLE public.dependency_graph_dirty_item
    ALTER COLUMN last_detected_at SET NOT NULL;

DROP INDEX IF EXISTS ux_dependency_graph_dirty_item_new_key;

CREATE UNIQUE INDEX IF NOT EXISTS ux_dependency_graph_dirty_item_day_module
    ON public.dependency_graph_dirty_item USING btree (business_date, source_kind, source_name, module_name);
