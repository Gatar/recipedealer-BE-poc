CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

-- unaccent() is only STABLE (it depends on the search path and the dictionary). Pinning the dictionary
-- makes the wrapper safe to declare IMMUTABLE, so it can be used in generated columns and indexes.
CREATE FUNCTION rd_unaccent(input text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN public.unaccent('public.unaccent'::regdictionary, input);

-- Text used for search and similarity: lower-case, without diacritics, single spaces.
CREATE FUNCTION rd_normalize(input text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
    RETURN btrim(regexp_replace(lower(rd_unaccent(input)), '\s+', ' ', 'g'));
