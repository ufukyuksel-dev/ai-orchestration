UPDATE code_symbols
SET fqn = ''
WHERE fqn IS NULL;

ALTER TABLE code_symbols
    ALTER COLUMN fqn SET DEFAULT '',
    ALTER COLUMN fqn SET NOT NULL;

ALTER TABLE code_symbols
    DROP CONSTRAINT IF EXISTS code_symbols_project_key_file_id_symbol_kind_name_signature_key;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'code_symbols_project_file_kind_name_fqn_signature_key'
          AND conrelid = 'code_symbols'::regclass
    ) THEN
        ALTER TABLE code_symbols
            ADD CONSTRAINT code_symbols_project_file_kind_name_fqn_signature_key
            UNIQUE (project_key, file_id, symbol_kind, name, fqn, signature);
    END IF;
END
$$;
