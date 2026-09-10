-- Apply in database loxone after creating the dedicated login and events table.
REVOKE ALL ON DATABASE loxone FROM PUBLIC;
GRANT CONNECT ON DATABASE loxone TO loxone_events;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO loxone_events;
GRANT INSERT, SELECT(id) ON events TO loxone_events;
GRANT SELECT, INSERT, UPDATE ON state_mapping TO loxone_events;
GRANT SELECT ON unresolved_states TO loxone_events;
