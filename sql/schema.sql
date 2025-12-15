-- AntiGrief SQL schema (bundled)
CREATE TABLE IF NOT EXISTS claims (
  owner_uuid VARCHAR(40) NOT NULL,
  minx INT NOT NULL, miny INT NOT NULL, minz INT NOT NULL,
  maxx INT NOT NULL, maxy INT NOT NULL, maxz INT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_claims_owner  ON claims(owner_uuid);
CREATE INDEX IF NOT EXISTS idx_claims_minmaxx ON claims(minx, maxx);
CREATE INDEX IF NOT EXISTS idx_claims_minmaxz ON claims(minz, maxz);

CREATE TABLE IF NOT EXISTS claimblocks (
  player_uuid VARCHAR(40) PRIMARY KEY,
  balance BIGINT NOT NULL
);
