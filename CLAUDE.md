# pgmalli-next

- Read DEBT.md before changing how arguments, literals or problems are typed: it lists what is
  known to be wrong. Fixing one, delete its entry in the same commit.
- Never refuse what PostgreSQL takes, but the rules of docs/queries.md "Stricter than PostgreSQL".
  A new strictness needs a rule there, which accept-test checks stricter.edn against.
- How to run the checks, and which need docker: CONTRIBUTING.md and `bb tasks`.
