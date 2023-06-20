(defproject ring-example "0.1.0-SNAPSHOT"
  :description "Reitit Http App with Swagger"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [ring/ring-jetty-adapter "1.7.1"]
                 [aleph "0.4.7-alpha5"]
                 [metosin/reitit "0.6.0a-hti-malli-swagger-defs"]
                 [metosin/ring-swagger-ui "5.0.0-alpha.0"]]
  :repl-options {:init-ns example.server})
