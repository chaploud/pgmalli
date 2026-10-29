(ns pgmalli.impl.ir-jdbc
  "ir.sql over JDBC, apart from pgmalli.impl.ir since babashka has no java.sql: loaded only
   when a db map asks for JDBC.")

(defn- connect [{:keys [jdbc-url datasource user password]}]
  (if datasource
    (.getConnection ^javax.sql.DataSource datasource)
    (do (try (Class/forName "org.postgresql.Driver")
             (catch ClassNotFoundException _
               ;; the URL is left out: it may carry a password
               (throw (ex-info "PostgreSQL's JDBC driver is not on the classpath: add org.postgresql/postgresql, or give psql settings instead of :jdbc-url" {}))))
        (java.sql.DriverManager/getConnection jdbc-url (doto (java.util.Properties.)
                                                         (cond-> user (.setProperty "user" (str user)))
                                                         (cond-> password (.setProperty "password" (str password))))))))

(defn- document [^java.sql.Statement st sql]
  (loop [result? (.execute st sql)]
    (cond result? (with-open [rs (.getResultSet st)]
                    (or (when (.next rs) (.getString rs 1)) (throw (ex-info "ir.sql returned no document" {}))))
          (= -1 (.getUpdateCount st)) (throw (ex-info "ir.sql returned no rows" {}))
          :else (recur (.getMoreResults st)))))

(defn out
  "The document ir.sql returns, read over JDBC. It runs in a transaction rolled back after, so
   its SET search_path does not stay on a pooled connection; a failure in putting the
   connection back is attached to the query's own, not thrown over it."
  [db sql]
  (with-open [^java.sql.Connection conn (connect db)]
    (let [auto-commit (.getAutoCommit conn)
          restore #(do (.rollback conn) (.setAutoCommit conn auto-commit))]
      (.setAutoCommit conn false)
      (let [doc (try (with-open [st (.createStatement conn)] (document st sql))
                     (catch Throwable e
                       (try (restore) (catch Throwable x (.addSuppressed e x)))
                       (throw e)))]
        (restore)
        doc))))
