# Loxone events

Java 21 service recording every state event exposed by a Loxone Miniserver into PostgreSQL database **loxone**, table **events**. Value, text, daytimer and weather tables are split into individual event records. Each row contains local UTC receipt time, state UUID, event type, decoded JSON and original binary bytes. Unknown event types and malformed messages are retained as raw events rather than discarded. Authentication responses and keepalives are not state events and are not stored.

The Miniserver only exposes states used in its app interface and permitted to the configured user. On connection it sends a current-state snapshot, then changes. Reconnection creates a new connection ID and records a new snapshot. It cannot replay changes that happened while disconnected; this is not an audit log of all PLC-internal activity. No state-changing control commands are sent.

## Operation

Build and test with `mvn verify`. Run with `java -jar target/loxone-events.jar /path/to/config.json`. Copy `config.example.json` to a private file, fill in credentials and use restrictive permissions. The Miniserver must support WSS and current JWT authentication (generation 2 / firmware 11.2.10.22 or later). TLS hostname and certificate validation remain enabled; use a certificate-valid hostname mapped to the LAN address if local DNS does not provide one.

The collector stores its client identity, token and a disk-backed event buffer in `state_directory`. Each complete received event packet is atomically written and fsynced before database insertion. Committed packets are removed; replay after a crash uses stable event IDs and `ON CONFLICT` to prevent duplicate inserts. PostgreSQL outages retry every five seconds while new events continue to spool. Keep sufficient disk space for outages. Shutdown before a packet is durably spooled, full disks, or Miniserver/network outages can leave gaps; raw parser failures are retained for investigation. Do not run two collectors against the same state directory.

Create the `loxone` database and apply `src/main/resources/schema.sql` as an administrator. The runtime database user only needs CONNECT, schema USAGE and INSERT on events plus SELECT(id) for idempotent conflict handling. Schema creation is deliberately separate from runtime. Passwords, tokens and full protocol responses are never logged. The Docker service opens no listening ports.

For real PostgreSQL integration tests, use a disposable database with `LOXONE_TEST_JDBC_URL`, `LOXONE_TEST_USER` and `LOXONE_TEST_PASSWORD` in the environment. Tests verify packet replay does not create duplicate rows; the unit suite covers UUID byte order, event tables, text padding, header handling and malformed payload preservation.

Useful queries:

```sql
SELECT received_at,event_type,event_uuid,payload FROM events ORDER BY received_at DESC LIMIT 20;
SELECT event_type,count(*) FROM events GROUP BY event_type;
SELECT pg_size_pretty(pg_database_size('loxone'));
```

Protocol reference: [Loxone Miniserver communication API v17](https://www.loxone.com/enen/wp-content/uploads/sites/3/2026/04/1700_Communicating-with-the-Miniserver.pdf).

Deployment on Aziz is managed by the private [tcox2/aziz](https://github.com/tcox2/aziz) Compose repository, which pins this repository as a Git submodule. Credentials and buffered events remain outside Git under `/opt/aziz/private/loxone-events/`.
