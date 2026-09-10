CREATE TABLE IF NOT EXISTS events (
    id uuid PRIMARY KEY,
    received_at timestamptz NOT NULL,
    stored_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    source text NOT NULL,
    connection_id uuid NOT NULL,
    message_number bigint NOT NULL,
    event_index integer NOT NULL,
    event_type integer NOT NULL,
    event_uuid text,
    payload jsonb NOT NULL,
    raw bytea NOT NULL,
    UNIQUE (connection_id, message_number, event_index)
);
CREATE INDEX IF NOT EXISTS events_received_at ON events USING brin(received_at);
CREATE INDEX IF NOT EXISTS events_uuid_received_at ON events(event_uuid,received_at DESC);
COMMENT ON TABLE events IS 'Append-only Loxone state events, including initial snapshots after each connection. Receipt time is local UTC; raw preserves each event record.';
