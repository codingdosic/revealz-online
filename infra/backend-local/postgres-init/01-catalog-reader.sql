DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'revealz_catalog') THEN
    CREATE ROLE revealz_catalog LOGIN PASSWORD 'local-catalog-only';
  END IF;
END
$$;

GRANT CONNECT ON DATABASE revealz_meta TO revealz_catalog;
GRANT USAGE ON SCHEMA public TO revealz_catalog;
ALTER DEFAULT PRIVILEGES FOR ROLE revealz_meta IN SCHEMA public
  GRANT SELECT ON TABLES TO revealz_catalog;
