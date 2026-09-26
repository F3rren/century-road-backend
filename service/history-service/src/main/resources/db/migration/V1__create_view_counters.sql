-- ============================================================================
-- Anonymous, aggregate view counters: how many times a calendar day's page or a
-- country was viewed, in total. No visitor identifier, no timestamp, no session -
-- each row is just a key and a running count, incremented atomically (see the
-- repositories' upsert queries). This is the entire persistence layer this
-- service has; everything else it serves comes straight from Wikipedia.
--
-- Own schema, not "public": this database is shared with auth-service, and a
-- schema keeps the two services' tables and Flyway histories from colliding.
-- See the flyway.schemas note in application.properties.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS history;

CREATE TABLE history.day_views (
    month       SMALLINT NOT NULL CHECK (month BETWEEN 1 AND 12),
    day         SMALLINT NOT NULL CHECK (day BETWEEN 1 AND 31),
    view_count  BIGINT   NOT NULL DEFAULT 0,
    PRIMARY KEY (month, day)
);

-- ISO 3166-1 alpha-2, the same code the frontend's map already keys every
-- country on (Natural Earth's iso_a2 property) - no translation table needed
-- between what a visitor clicks and what gets counted here.
CREATE TABLE history.country_views (
    country_code  VARCHAR(2) PRIMARY KEY CHECK (country_code ~ '^[A-Z]{2}$'),
    view_count    BIGINT NOT NULL DEFAULT 0
);
