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
- **A literal beside an operand of another type in coalesce or CASE.** `[:coalesce :uuid_col 1]`
  is not reported; PostgreSQL refuses it (types uuid and integer cannot be matched).
  `args/resolving-args` checks a literal against the operands' common type, and there is none.
  Report where `query/common` finds no common type.
- **Literals no position types yet.** Each is refused by PostgreSQL and not reported:
  - a literal item of `INSERT INTO t (c) SELECT 'x'`
  - `[:coalesce "a" 1]`, of literals alone
  - `[:= :ints [:array [1.5]]]`, an `ARRAY[...]` of literals against an array column
  - `[:round :d 2]` with `d` a double precision column (`round(double precision, integer)` does not exist)
  - `[:coalesce :c "a"]` with `c` a `"char"` column
  - `[:= :x 1]` with `x` an xml column
  - `[:like :m "o%"]` with `m` an enum column

## Types left untold or loose

- **A domain over an array of a domain is untyped.** `CREATE DOMAIN codes AS code[]` with `code`
  a domain: ir.sql's domain chain stops at `code[]`, which is no domain, so the column is `:any`
  and listed in `:unrendered`. Follow an array's element through its domains too.
