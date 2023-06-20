(defproject ring-example "0.1.0-SNAPSHOT"
  :description "Reitit-http with pedestal"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [io.pedestal/pedestal.service "0.5.5"]
                 [io.pedestal/pedestal.jetty "0.5.5"]
                 [metosin/reitit-pedestal "0.6.0a-hti-malli-swagger-defs"]
                 [metosin/reitit "0.6.0a-hti-malli-swagger-defs"]]
  :repl-options {:init-ns example.server})
