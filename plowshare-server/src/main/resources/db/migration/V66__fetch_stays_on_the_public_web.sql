-- Fetch stays on the public web: implementation rationale §2.8.
--
-- Before this version fetch could reach loopback, RFC 1918 ranges and link-local addresses,
-- including the cloud metadata address. FetchService.read serves a stored page without
-- dialling, and fetched_pages is server-wide, so a page read from a private address before
-- the guard shipped would stay readable to every project for up to plowshare.fetch.ttl.
--
-- The table is a cache. Emptying it costs a refetch and nothing else. Flyway runs this once;
-- pages stored after it are ordinary cache entries and are kept.

DELETE FROM fetched_pages;
