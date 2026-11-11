# pgmalli

- Read the open issues labeled `debt` (`gh issue list --label debt`) before changing how
  arguments, literals or problems are typed: they list what is known to be wrong. Fixing one,
  close it from the commit (`Fixes #n`).
- Never refuse what PostgreSQL takes, but the rules of docs/queries.md "Stricter than PostgreSQL".
  A new strictness needs a rule there, which accept-test checks stricter.edn against.
- How to run the checks, and which need docker: CONTRIBUTING.md and `bb tasks`.
