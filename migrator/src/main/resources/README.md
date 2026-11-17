
Docker run command example:

```shell
docker run -v ./migration-data:/tb-migrator/data --env-file tb-migrator.env thingsboard/tb-migrator:latest
```


Env file example for tenant export:
```
MIGRATOR_MODE=TENANT_DATA_EXPORT
EXPORTED_TENANT_ID=<TENANT_ID>
SKIPPED_TABLES=RULE_NODE_DEBUG_EVENT,STATS_EVENT,LC_EVENT

DATABASE_URL=jdbc:postgresql://<POSTGRES_HOST>:5432/thingsboard
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=postgres

CASSANDRA_EXPORT_ENABLED=true

CASSANDRA_URL=<CASSANDRA_HOST>:9042
CASSANDRA_LOCAL_DATACENTER=us-east
CASSANDRA_KEYSPACE_NAME=thingsboard
```


Env file example for tenant export from a PostgreSQL-only (or TimescaleDB) setup, to be imported into a hybrid (PostgreSQL + Cassandra) one.
Time-series history is read from `ts_kv` and written in the same format as the Cassandra export, so the import is the same as below.
```
MIGRATOR_MODE=TENANT_DATA_EXPORT
EXPORTED_TENANT_ID=<TENANT_ID>
SKIPPED_TABLES=RULE_NODE_DEBUG_EVENT,STATS_EVENT,LC_EVENT

DATABASE_URL=jdbc:postgresql://<POSTGRES_HOST>:5432/thingsboard
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=postgres

CASSANDRA_EXPORT_ENABLED=false
POSTGRES_TS_EXPORT_ENABLED=true
POSTGRES_TS_EXPORT_PARTITIONING=MONTHS
```
- `POSTGRES_TS_EXPORT_PARTITIONING` must equal `TS_KV_PARTITIONING` of the target cluster. Otherwise the import succeeds, but the history is not readable.
- Optional: `POSTGRES_TS_EXPORT_FILTER_START_TS` / `POSTGRES_TS_EXPORT_FILTER_END_TS` (inclusive, epoch millis), `POSTGRES_TS_EXPORT_BATCH_SIZE` (default 10000).
- History is exported only for keys present in `ts_kv_latest`. Keys without a latest value are not exported, e.g. keys saved by
  the "Save time series" node with the latest processing strategy set to skip, or keys whose latest value was deleted.
- If any key fails to export, the migrator exits with an error, deletes the time-series files and doesn't create `data.tar`.
- `DATABASE_MAX_POOL_SIZE` (default 16) must be greater than `PARALLELISM_LEVEL`, the export checks it at startup.
- `POSTGRES_EXPORT_DELAY_BETWEEN_QUERIES` also throttles the time-series export: it sleeps after every `ts_kv` query.
- Each key is one query (per `POSTGRES_TS_EXPORT_BATCH_SIZE` rows) that probes every `ts_kv` partition or TimescaleDB chunk.
  Measured on PostgreSQL 18 with 12 threads: about 15000 keys/s with 36 monthly partitions, about 500 keys/s with 1096 daily ones.

Two-phase migration (full export at T1, then a time-series delta after devices are switched over):
- Use a separate `WORKING_DIRECTORY` for the delta, `data.tar` is overwritten otherwise.
- Set `POSTGRES_TS_EXPORT_FILTER_START_TS` to T1 minus a safety margin: `ts` is the device timestamp, so late-arriving data may have `ts` < T1. Importing overlapping points again is safe.
- Either keep `POSTGRES_EXPORT_ENABLED=true` (fresh key list; add non-entity tables to `SKIPPED_TABLES` to speed it up), or set `POSTGRES_EXPORT_ENABLED=false` and put `ts_kv_latest.gz` from the full export into the working directory.
  Key ids are resolved by name in this database's `key_dictionary`, so the ids in that file don't matter.
- Import the delta with `POSTGRES_IMPORT_ENABLED=false`. Latest values (`ts_kv_latest`) of the target are not updated by it.


Before importing, make sure to create (and then delete) a tenant so that system roles are created.

Env file example for tenant import:
```
MIGRATOR_MODE=TENANT_DATA_IMPORT
SKIPPED_TABLES=RULE_NODE_DEBUG_EVENT,STATS_EVENT,LC_EVENT

POSTGRES_IMPORT_ENABLED=true

DATABASE_URL=jdbc:postgresql://<POSTGRES_HOST>:5432/thingsboard
DATABASE_USERNAME=postgres
DATABASE_PASSWORD=postgres

CASSANDRA_IMPORT_ENABLED=true
CASSANDRA_IMPORT_TS_KV_TTL=730

CASSANDRA_URL=cassandra:9042
CASSANDRA_LOCAL_DATACENTER=us-east
CASSANDRA_KEYSPACE_NAME=thingsboard
```


Time-series files:
- Both time-series exports write `ts_kv_00001.gz`, `ts_kv_00002.gz`, ... of about `TS_KV_EXPORT_MAX_FILE_SIZE_MB` (default 256) each, and `ts_kv_manifest.gz` listing them with row counts.
  Each worker thread compresses into its own files, so the last file of each thread is smaller.
  The manifest is written last, so the import rejects files without it as an incomplete export.
- Older migrator versions can't import this format: they expect a single `ts_kv.gz`. For them, export with `TS_KV_EXPORT_MAX_FILE_SIZE_MB=0`.
  The import reads both formats.
- Extract each export into an empty working directory. If files of two exports are mixed (both `ts_kv_manifest.gz` and `ts_kv.gz`), the import fails.
- `CASSANDRA_IMPORT_TS_KV_SKIP_LINES` counts rows across all files in order, like the processed counter in the import log. Fully skipped files are not read.
- `data.tar` is limited to about 2GB, use `ARCHIVE_RESULT=false` for larger exports and transfer the files as they are.
- For time-based slices instead of size-based ones, run exports with the `*_FILTER_START_TS` / `*_FILTER_END_TS` filters into separate working directories.


Cassandra versions:
- The exported files don't depend on the Cassandra version. The import writes them with CQL through the Java driver, which negotiates the protocol version with the cluster, so Cassandra 4.x and 5.x work without configuration.
- To pin the protocol version, e.g. for a cluster in the middle of a rolling upgrade, set `JAVA_OPTS=-Ddatastax-java-driver.advanced.protocol.version=V4` (or `V5`).
- Cassandra 4.x, and 5.x with the default `storage_compatibility_mode: CASSANDRA_4`, reject writes that expire after 2038-01-19,
  so `CASSANDRA_IMPORT_TS_KV_TTL_DAYS` must keep the import time plus TTL before that date. Cassandra 5.x with `storage_compatibility_mode: NONE` doesn't have this limit.