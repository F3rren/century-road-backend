-- "Segnala un errore": what a visitor reports about an event, an insight or a path. Written by
-- ReportService, read by the person running the site, in SQL, until there is an admin screen
-- for it. Additive: nothing here touches the tables before it.
--
-- What is stored is the report and what it is about - and nothing that identifies the visitor,
-- no address, no session. `contact` is the one exception, and only when the visitor chose to
-- leave an email address to be answered at: it exists to be used once and then removed
-- (UPDATE ... SET contact = NULL once handled), not to be kept.
CREATE TABLE history.error_reports (
    id              BIGSERIAL     PRIMARY KEY,

    -- What the report is about. An EVENT is one of Wikipedia's entries for a day, and has no
    -- identifier of its own, so it is named by its date, its edition and, as a help in finding
    -- it again, its text as the visitor saw it. An INSIGHT or a PATH is named by its slug.
    target_type     VARCHAR(10)   NOT NULL CHECK (target_type IN ('EVENT', 'INSIGHT', 'PATH')),
    target_slug     VARCHAR(100),
    target_year     INTEGER,
    target_month    SMALLINT      CHECK (target_month BETWEEN 1 AND 12),
    target_day      SMALLINT      CHECK (target_day BETWEEN 1 AND 31),
    target_language VARCHAR(2)    CHECK (target_language IN ('it', 'en')),
    target_text     VARCHAR(500),

    category        VARCHAR(20)   NOT NULL
                    CHECK (category IN ('WRONG_DATE', 'WRONG_PLACE', 'WRONG_TEXT', 'BROKEN_LINK', 'OTHER')),
    message         VARCHAR(1000) NOT NULL,
    contact         VARCHAR(254),

    created_at      TIMESTAMPTZ   NOT NULL,
    -- NULL until somebody has dealt with it.
    handled_at      TIMESTAMPTZ,

    -- An event is named by its date, the other two by their slug: never both, never neither.
    CONSTRAINT error_reports_target_chk CHECK (
        (target_type = 'EVENT' AND target_slug IS NULL AND target_year IS NOT NULL
            AND target_month IS NOT NULL AND target_day IS NOT NULL AND target_language IS NOT NULL)
        OR (target_type <> 'EVENT' AND target_slug IS NOT NULL AND target_year IS NULL
            AND target_month IS NULL AND target_day IS NULL AND target_language IS NULL
            AND target_text IS NULL))
);

-- The read path is "what is waiting for me, oldest first".
CREATE INDEX error_reports_open_idx ON history.error_reports (created_at) WHERE handled_at IS NULL;
