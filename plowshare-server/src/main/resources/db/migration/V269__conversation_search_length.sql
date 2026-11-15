-- BM25 length uses the same retained lexemes/positions as term frequency. PostgreSQL
-- caps positions per lexeme; both statistics deliberately share that representation.
CREATE FUNCTION conversation_search_length(value tsvector) RETURNS integer
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
  SELECT coalesce(sum(greatest(cardinality(positions), 1)), 0)::integer
    FROM unnest(value)
$$;

ALTER TABLE entries ADD COLUMN search_length integer GENERATED ALWAYS AS
  (conversation_search_length(to_tsvector('english', content))) STORED;
