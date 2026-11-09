(ns pgmalli-next.test-jdbc
  "next.jdbc's functions the tests run SQL by, each parameter sent as pgmalli-next.jdbc/sent sends
   it, as an application does. JVM only."
  (:require [next.jdbc :as jdbc]
            [pgmalli-next.jdbc :as pgmalli-jdbc]))

(defn- sent
  "sql-params as sent sends them: [sql & params], or the params alone of a PreparedStatement."
  [sql-params]
  (if (string? (first sql-params))
    (pgmalli-jdbc/sent sql-params)
    (subvec (pgmalli-jdbc/sent (into [""] sql-params)) 1)))

(defn execute!
  ([connectable sql-params] (jdbc/execute! connectable (sent sql-params)))
  ([connectable sql-params opts] (jdbc/execute! connectable (sent sql-params) opts)))

(defn execute-one!
  ([connectable sql-params] (jdbc/execute-one! connectable (sent sql-params)))
  ([connectable sql-params opts] (jdbc/execute-one! connectable (sent sql-params) opts)))

(defn prepare
  ([connection sql-params] (jdbc/prepare connection (sent sql-params)))
  ([connection sql-params opts] (jdbc/prepare connection (sent sql-params) opts)))

(defn plan
  ([connectable sql-params] (jdbc/plan connectable (sent sql-params)))
  ([connectable sql-params opts] (jdbc/plan connectable (sent sql-params) opts)))

(def get-connection jdbc/get-connection)

(def get-datasource jdbc/get-datasource)
