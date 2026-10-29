(ns pgmalli.impl.ir-jdbc
  "ir.sql over JDBC, apart from pgmalli.impl.ir since babashka has no java.sql: loaded only
   when a db map asks for JDBC."
  (:require [pgmalli.impl.error :as error]))

(defn- target
  "host:port/db of a JDBC URL, without its user, password or parameters."
  [url]
  (let [[_ hosts db] (re-find #"^jdbc:postgresql://(?:[^@/]*@)?([^/?]*)/?([^?]*)" (str url))]
    (if hosts (str hosts "/" db) "the :jdbc-url")))

(defn- connect [{:keys [jdbc-url datasource user password]}]
  (if datasource
    (try (.getConnection ^javax.sql.DataSource datasource)
         (catch java.sql.SQLException e
           (error/fail! :pgmalli.error/db-unavailable
                        (str "cannot connect through :db :datasource: " (ex-message e) "; check the DataSource's settings and that PostgreSQL is running")
                        {} e)))
    (do (try (Class/forName "org.postgresql.Driver")
             (catch ClassNotFoundException _
               (error/fail! :pgmalli.error/invalid-config "PostgreSQL's JDBC driver is not on the classpath: add org.postgresql/postgresql, or give psql settings instead of :jdbc-url")))
        (try (java.sql.DriverManager/getConnection jdbc-url (doto (java.util.Properties.)
                                                              (cond-> user (.setProperty "user" (str user)))
                                                              (cond-> password (.setProperty "password" (str password)))))
             (catch java.sql.SQLException e
               (error/fail! :pgmalli.error/db-unavailable
                            ;; DriverManager's "No suitable driver" message carries the URL, and the URL may carry a password
                            (str "cannot connect to " (target jdbc-url) ": " (.replace (str (ex-message e)) (str jdbc-url) "the URL")
                                 "; check :db :jdbc-url, :user and :password in pgmalli.edn and that PostgreSQL is running")
                            {:given (target jdbc-url)} e))))))

(defn- document [^java.sql.Statement st sql]
  (loop [result? (.execute st sql)]
    (cond result? (with-open [rs (.getResultSet st)]
                    (or (when (.next rs) (.getString rs 1)) (error/fail! :pgmalli.error/fault "ir.sql returned no document over JDBC: a pgmalli bug; please report it")))
          (= -1 (.getUpdateCount st)) (error/fail! :pgmalli.error/fault "ir.sql returned no rows over JDBC: a pgmalli bug; please report it")
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
