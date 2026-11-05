# Debt

What pgmalli-next knows it gets wrong and has not fixed yet. This repository is a temporary
branch of pgmalli: these would be issues there. Move them to issues, or delete this file, when the
work goes back.

None of these refuses what PostgreSQL takes. Each lets through something PostgreSQL refuses
(it fails when the query runs), or leaves a type untold. Fix one, then delete its entry.

## Accepted where PostgreSQL refuses

- **A function returning a domain over an enum, compared.** `f(m) = 'happy'` passes; PostgreSQL
  refuses it, as for a column of the domain. ir.sql names a function's result by its domain's base
  type (`type_bases`), so the domain is lost. Keep the domain beside the base type, as a column's
  `:pg/domain` does.

## Types left untold or loose

- **A domain over an array of a domain is untyped.** `CREATE DOMAIN codes AS code[]` with `code`
  a domain: ir.sql's domain chain stops at `code[]`, which is no domain, so the column is `:any`
  and listed in `:unrendered`. Follow an array's element through its domains too.
