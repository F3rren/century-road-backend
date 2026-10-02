-- The country index behind "Il mio secolo": for each Wikipedia edition, every event with a
-- year that the map's own rule places in a country (first linked article with coordinates,
-- inside a Natural Earth 1:110m shape - see CountryLocator). Derived data, rebuilt nightly by
-- TimelineIndexer one (language, day) at a time, DELETE + INSERT in one transaction. Safe to
-- TRUNCATE: an empty table is rebuilt at the next start. Wikipedia text only, nothing about
-- visitors.
CREATE TABLE history.timeline_events (
    id           BIGSERIAL   PRIMARY KEY,
    language     VARCHAR(2)  NOT NULL CHECK (language IN ('it', 'en')),
    month        SMALLINT    NOT NULL CHECK (month BETWEEN 1 AND 12),
    day          SMALLINT    NOT NULL CHECK (day BETWEEN 1 AND 31),
    year         INTEGER     NOT NULL,
    country_code VARCHAR(2)  NOT NULL CHECK (country_code ~ '^[A-Z]{2}$'),
    text         TEXT        NOT NULL,
    indexed_at   TIMESTAMPTZ NOT NULL
);

-- The read path: one country's timeline in one language, in date order. The nightly DELETE by
-- (language, month, day) scans ~14k rows in about a millisecond, so it gets no index of its own.
CREATE INDEX timeline_events_country_idx
    ON history.timeline_events (language, country_code, year, month, day);
