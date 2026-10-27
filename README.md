# Search Engine Project — Stage 2: Service-Oriented Architecture

**Big Data · Degree in Data Science and Engineering · Universidad de Las Palmas de Gran Canaria · 2026–2027**

**Group ZCJ:** Claudia Álvarez González · Zayra Penya Juan · Javier Fernández Muñoz

Stage 2 turns the data layer of Stage 1 into cooperating microservices with REST APIs,
written in **Java 17** with **Javalin** and **Gson**. All the processes run on a single machine.

## Services

| Module | Port | Responsibility |
|--------|------|----------------|
| `control-module` | 7000 | Orchestrates the pipeline: ingestion → indexing → publication |
| `ingestion-service` | 7001 | Downloads books from Project Gutenberg into the datalake |
| `indexing-service` | 7002 | Extracts metadata and updates the inverted index in the datamart |
| `search-service` | 7003 | Front-end API: keyword search with filters and pagination |
| `common` | — | Shared code: configuration, tokenizer, book parsing, datalake layout, database schema, error format |

```text
                 front-end
                     │  GET /search, /books/{id}
                     ▼
              ┌─────────────┐
              │   Search    │ ◄──── reads ────┐
              └─────────────┘                 │
                                     datamarts/search.db (SQLite)
┌─────────┐  POST /ingest/{id}  ┌───────────┐  │
│ Control │ ──────────────────► │ Ingestion │ ─┼─► datalake/ranges/...
│ Module  │  POST /index/...    └───────────┘  │
│         │ ──────────────────► ┌───────────┐  │
└─────────┘                     │ Indexing  │ ─┘ writes
                                └───────────┘
```

## Requirements

- **Java 17 or newer** (`java -version`).
- Maven is **not** needed: the repository includes the Maven Wrapper (`mvnw` / `mvnw.cmd`),
  which downloads Maven 3.9.9 the first time it runs.

## Build

From the repository root:

```bash
./mvnw clean package          # Linux / macOS / Git Bash
mvnw.cmd clean package        # Windows (cmd or PowerShell)
```

This compiles every module, runs the tests and creates one executable jar per service:

```text
control-module/target/control-module-1.0.0.jar
ingestion-service/target/ingestion-service-1.0.0.jar
indexing-service/target/indexing-service-1.0.0.jar
search-service/target/search-service-1.0.0.jar
```

## Run

Start every service in its own terminal, **all from the repository root**, so that they
share the same `datalake/` and `datamarts/` folders:

```bash
java -jar ingestion-service/target/ingestion-service-1.0.0.jar
java -jar indexing-service/target/indexing-service-1.0.0.jar
java -jar search-service/target/search-service-1.0.0.jar
java -jar control-module/target/control-module-1.0.0.jar
```

Check that they are running:

```bash
curl http://localhost:7000/status
curl http://localhost:7001/status
curl http://localhost:7002/status
curl http://localhost:7003/status
```

## Configuration

Every service reads the same environment variables:

| Variable | Default | Meaning |
|----------|---------|---------|
| `PORT` | 7000 / 7001 / 7002 / 7003 | Port of the service |
| `DATALAKE_PATH` | `datalake` | Root folder of the datalake |
| `DB_PATH` | `datamarts/search.db` | SQLite datamart (metadata + inverted index) |
| `INGESTION_URL` | `http://localhost:7001` | Used by the Control Module |
| `INDEXING_URL` | `http://localhost:7002` | Used by the Control Module |
| `SEARCH_URL` | `http://localhost:7003` | Base URL of the Search Service |

Example (Windows PowerShell): `$env:PORT=8003; java -jar search-service/target/search-service-1.0.0.jar`

## Shared conventions

These are agreed by the whole group; the rest of each service is up to its owner.

**Datalake** (range-based layout selected in Stage 1):

```text
datalake/ranges/001000-001999/1342.header.txt
datalake/ranges/001000-001999/1342.body.txt
```

**Datamart** (`datamarts/search.db`): Indexing writes, Search reads.

```sql
books(book_id INTEGER PRIMARY KEY, title TEXT, author TEXT,
      language TEXT,     -- ISO code: 'en', 'es', 'fr'...
      year INTEGER,      -- year of the Gutenberg "Release date"
      path TEXT)
postings(term TEXT, book_id INTEGER, positions TEXT,   -- "10,250,300"
         PRIMARY KEY (term, book_id))
```

The database uses WAL mode: a book becomes searchable only when the transaction that
indexes it is committed, so Search never sees a half-updated index.

**Tokenizer**: lowercase, every character outside `a-z` is a separator (same as Stage 1).

**JSON**: field names in `snake_case` (`book_id`, `books_indexed`). Every error uses the
same body:

```json
{"error": "invalid_parameter", "message": "limit must be between 1 and 100"}
```

**Benchmarks**: JMH benchmarks go in `src/test/java` of each module, never in `src/main`.

## Sample dataset

`sample_data/` contains five Gutenberg books (11, 84, 98, 1342, 1661) for offline tests.
It is the only copy of the dataset in the repository.

## Repository layout

```text
stage_2/
├── pom.xml                 # parent: shared versions, builds every module
├── mvnw, mvnw.cmd, .mvn/   # Maven Wrapper
├── sample_data/
├── common/
├── control-module/
├── ingestion-service/
├── indexing-service/
└── search-service/
```

## Workflow

Each member works on their own branch (`ingestion`, `indexing`, `search`) and merges into
`main` through Pull Requests.
