(ns pgmalli-next.impl.ir-jdbc
  "ir.sql over JDBC, apart from pgmalli-next.impl.ir since babashka has no java.sql: loaded only
   when a db map asks for JDBC."
  (:require [clojure.string :as str]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.ir :as ir]))

(defn- target
  "host:port/db of a JDBC URL, without its user, password or parameters."
  [url]
  (let [[_ hosts db] (re-find #"(?i)^jdbc:postgresql://(?:[^@/]*@)?([^/?]*)/?([^?]*)" (str url))]
    (if hosts (str hosts "/" db) "the :jdbcUrl")))

(defn- without-urls
  "e as a cause that names no URL: DriverManager's \"No suitable driver\" message carries the
   URL, and a URL may carry a password. Its message with every URL (jdbc:..., scheme://...)
   replaced, and its SQLState."
  ^java.sql.SQLException [^java.sql.SQLException e]
  (java.sql.SQLException. (str/replace (str (ex-message e)) #"(?i)(?:jdbc:|[a-z][a-z0-9+.-]*://)\S+" "the URL") (.getSQLState e)))

(defn- connect [{:keys [jdbcUrl datasource user password]}]
  (if datasource
    (try (.getConnection ^javax.sql.DataSource datasource)
         (catch java.sql.SQLException e
           (let [c (without-urls e)]
             (error/fail! :pgmalli.error/db-unavailable
                          (str "cannot connect through :db :datasource: " (ex-message c) "; check the DataSource's settings and that PostgreSQL is running")
                          {} c))))
    (do (when-not (re-find #"(?i)^jdbc:postgresql:" (str jdbcUrl))
          ;; not echoed: a URL of another scheme (postgres://user:password@host) may carry a password
          (error/fail! :pgmalli.error/invalid-config ":db's :jdbcUrl is no URL of PostgreSQL's JDBC driver: write jdbc:postgresql://host:port/db (a postgres:// URL is libpq's; its user and password go in :user and :password)"))
        (try (Class/forName "org.postgresql.Driver")
             (catch ClassNotFoundException _
               (error/fail! :pgmalli.error/invalid-config "PostgreSQL's JDBC driver is not on the classpath: add org.postgresql/postgresql, or give psql settings instead of :jdbcUrl")))
        (try (java.sql.DriverManager/getConnection jdbcUrl (doto (java.util.Properties.)
                                                              (cond-> user (.setProperty "user" (str user)))
                                                              (cond-> password (.setProperty "password" (str password)))))
             (catch java.sql.SQLException e
               (let [c (without-urls e)]
                 (error/fail! :pgmalli.error/db-unavailable
                              (str "cannot connect to " (target jdbcUrl) ": " (ex-message c)
                                   "; check :db's :jdbcUrl, :user and :password and that PostgreSQL is running")
                              {:given (target jdbcUrl)} c)))))))

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
                       (if (instance? java.sql.SQLException e)
                         (error/fail! :pgmalli.error/fault
                                      (str "reading the schema over JDBC failed: " (ex-message (without-urls e)) "; " (ir/needs))
                                      {} (without-urls e))
                         (throw e))))]
        (restore)
        doc))))
