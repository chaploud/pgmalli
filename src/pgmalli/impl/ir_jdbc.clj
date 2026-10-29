(ns pgmalli.impl.ir-jdbc
  "ir.sql over JDBC, apart from pgmalli.impl.ir since babashka has no java.sql: loaded only
   when a db map asks for JDBC.")

(defn out
  "The document ir.sql returns, read over JDBC. It runs in a transaction rolled back after, so
   its SET search_path does not stay on a pooled connection."
  [{:keys [jdbc-url datasource user password]} sql]
  (with-open [conn (if datasource
                     (.getConnection datasource)
                     (java.sql.DriverManager/getConnection jdbc-url (doto (java.util.Properties.)
                                                                      (cond-> user (.setProperty "user" (str user)))
                                                                      (cond-> password (.setProperty "password" (str password))))))]
    (let [auto-commit (.getAutoCommit conn)]
      (.setAutoCommit conn false)
      (try
        (with-open [st (.createStatement conn)]
          (loop [result? (.execute st sql)]
            (cond result? (with-open [rs (.getResultSet st)] (.next rs) (.getString rs 1))
                  (= -1 (.getUpdateCount st)) (throw (ex-info "ir.sql returned no rows" {}))
                  :else (recur (.getMoreResults st)))))
        (finally (.rollback conn) (.setAutoCommit conn auto-commit))))))
