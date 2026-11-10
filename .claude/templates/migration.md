# Migration: {{migration_name}}

## Type
{{Database Schema | Data Backfill | API Version | Breaking Frontend Contract}}

## Reason
{{why this migration is necessary}}

## Pre-Migration State
{{schema/contract before}}

## Post-Migration State
{{schema/contract after}}

## Steps
1. {{step}}
2. {{step}}

## Alembic Revision
```
Revision ID: {{id}}
Down Revision: {{parent_id}}
```

## Backward Compatibility Window
{{how long old and new must coexist; dual-write/dual-read strategy if applicable}}

## Rollback Procedure
{{explicit steps}}

## Verification
{{how to confirm the migration succeeded in each environment}}
