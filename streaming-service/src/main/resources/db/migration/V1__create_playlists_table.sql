CREATE TABLE playlists
(
    id             BIGSERIAL PRIMARY KEY,
    username       VARCHAR(64)  NOT NULL,
    raw_path       VARCHAR(255) NOT NULL UNIQUE,
    playlist_path  VARCHAR(255) NOT NULL UNIQUE,
    thumbnail_path VARCHAR(255),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_playlists_username ON playlists (username);