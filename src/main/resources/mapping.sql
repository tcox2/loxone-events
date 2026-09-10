CREATE TABLE IF NOT EXISTS state_mapping (
 source text NOT NULL,
 event_uuid text NOT NULL,
 mappings jsonb NOT NULL DEFAULT '[]',
 first_seen_at timestamptz,
 last_seen_at timestamptz,
 metadata_updated_at timestamptz,
 present_in_structure boolean NOT NULL DEFAULT false,
 PRIMARY KEY(source,event_uuid)
);
CREATE OR REPLACE FUNCTION track_event_state() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
BEGIN
 IF NEW.event_uuid IS NOT NULL THEN
  INSERT INTO public.state_mapping(source,event_uuid,first_seen_at,last_seen_at)
  VALUES(NEW.source,NEW.event_uuid,NEW.received_at,NEW.received_at)
  ON CONFLICT(source,event_uuid) DO UPDATE SET
   first_seen_at=least(state_mapping.first_seen_at,EXCLUDED.first_seen_at),
   last_seen_at=greatest(state_mapping.last_seen_at,EXCLUDED.last_seen_at);
 END IF;
 RETURN NEW;
END $$;
CREATE OR REPLACE TRIGGER events_track_state AFTER INSERT ON events
FOR EACH ROW EXECUTE FUNCTION track_event_state();
INSERT INTO state_mapping(source,event_uuid,first_seen_at,last_seen_at)
SELECT source,event_uuid,min(received_at),max(received_at) FROM events WHERE event_uuid IS NOT NULL GROUP BY 1,2
ON CONFLICT(source,event_uuid) DO UPDATE SET
 first_seen_at=least(state_mapping.first_seen_at,EXCLUDED.first_seen_at),
 last_seen_at=greatest(state_mapping.last_seen_at,EXCLUDED.last_seen_at);
CREATE OR REPLACE VIEW unresolved_states AS
SELECT * FROM state_mapping WHERE first_seen_at IS NOT NULL AND NOT present_in_structure;
