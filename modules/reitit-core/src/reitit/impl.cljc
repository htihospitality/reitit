(ns ^:no-doc reitit.impl
  #?(:cljs (:require-macros [reitit.impl]))
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [meta-merge.core :as mm]
            [reitit.exception :as ex]
            [reitit.trie :as trie])
  #?(:clj
     (:import (java.net URLEncoder URLDecoder)
              (java.util HashMap Map))))

(defn parse [path opts]
  (let [path #?(:clj (.intern ^String (trie/normalize path opts)) :cljs (trie/normalize path opts))
        path-parts (trie/split-path path opts)
        path-params (->> path-parts (remove string?) (map :value) set)]
    {:path-params path-params
     :path-parts path-parts
     :path path}))

(defn wild-path? [path opts]
  (-> path (parse opts) :path-params seq boolean))

(defn ->wild-route? [opts]
  (fn [[path]] (-> path (parse opts) :path-params seq boolean)))

(defn maybe-map-values
  "Applies a function to every value of a map, updates the value if not nil.
  Also works on vectors. Maintains key for maps, order for vectors."
  [f coll]
  (reduce-kv
   (fn [coll k v]
     (if-some [v' (f v)]
       (assoc coll k v')
       coll))
   coll
   coll))

(defn walk [raw-routes {:keys [path data routes expand]
                        :or {data [], routes []}
                        :as opts}]
  (letfn
   [(walk-many [p m r]
      (reduce #(into %1 (walk-one p m %2)) [] r))
    (walk-one [pacc macc routes]
      (if (vector? (first routes))
        (walk-many pacc macc routes)
        (when (string? (first routes))
          (let [[path & [maybe-arg :as args]] routes
                [data childs] (if (or (vector? maybe-arg)
                                      (and (sequential? maybe-arg)
                                           (sequential? (first maybe-arg)))
                                      (nil? maybe-arg))
                                [{} args]
                                [maybe-arg (rest args)])
                macc (into macc (expand data opts))
                child-routes (walk-many (str pacc path) macc (keep identity childs))]
            (if (seq childs) (seq child-routes) [[(str pacc path) macc]])))))]
    (walk-one path (mapv identity data) raw-routes)))

(defn map-data [f routes]
  (mapv (fn [[p ds]] [p (f p ds)]) routes))

(defn meta-merge [left right opts]
  ((or (:meta-merge opts) mm/meta-merge) left right))

(defn merge-data [opts p x]
  (reduce
   (fn [acc [k v]]
     (try
       (meta-merge acc {k v} opts)
       (catch #?(:clj Exception, :cljs js/Error) e
         (ex/fail! ::merge-data {:path p, :left acc, :right {k v}, :exception e}))))
   {} x))

(defn resolve-routes [raw-routes {:keys [coerce] :as opts}]
  (cond->> (->> (walk raw-routes opts) (map-data #(merge-data opts %1 %2)))
    coerce (into [] (keep #(coerce % opts)))))

(defn path-conflicting-routes [routes opts]
  (let [parts-and-routes (mapv (fn [[s :as r]] [(trie/split-path s opts) r]) routes)]
    (-> (into {} (comp (map-indexed (fn [index [p r]]
                                      [r (reduce
                                          (fn [acc [p' r']]
                                            (if (trie/conflicting-parts? p p')
                                              (conj acc r') acc))
                                          #{} (subvec parts-and-routes (inc index)))]))
                       (filter (comp seq second))) parts-and-routes)
        (not-empty))))

(defn unresolved-conflicts [path-conflicting]
  (-> (into {}
            (remove (fn [[[_ route-data] conflicts]]
                      (and (:conflicting route-data)
                           (every? (comp :conflicting second)
                                   conflicts))))
            path-conflicting)
      (not-empty)))

(defn conflicting-paths [conflicts]
  (->> (for [[p pc] conflicts]
         (conj (map first pc) (first p)))
       (apply concat)
       (set)))

(defn name-conflicting-routes [routes]
  (some->> routes
           (group-by (comp :name second))
           (remove (comp nil? first))
           (filter (comp pos? count butlast second))
           (seq)
           (map (fn [[k v]] [k (set v)]))
           (into {})))

(defn find-names [routes _]
  (into [] (keep #(-> % second :name)) routes))

(defn compile-route [[p m :as route] {:keys [compile] :as opts}]
  [p m (if compile (compile route opts))])

(defn compile-routes [routes opts]
  (into [] (keep #(compile-route % opts) routes)))

(defn uncompile-routes [routes]
  (mapv (comp vec (partial take 2)) routes))

(defn path-for [route path-params]
  (if (:path-params route)
    (if-let [parts (reduce
                    (fn [acc part]
                      (if (string? part)
                        (conj acc part)
                        (if-let [p (get path-params (:value part))]
                          (conj acc p)
                          (reduced nil))))
                    [] (:path-parts route))]
      (apply str parts))
    (:path route)))

(defn throw-on-missing-path-params [template required path-params]
  (when-not (every? #(contains? path-params %) required)
    (let [defined (-> path-params keys set)
          missing (set/difference required defined)]
      (ex/fail!
       (str "missing path-params for route " template " -> " missing)
       {:path-params path-params, :required required}))))

(defn fast-assoc
  #?@(:clj  [[^clojure.lang.Associative a k v] (.assoc a k v)]
      :cljs [[a k v] (assoc a k v)]))

(defn fast-map [m]
  #?(:clj  (let [m (or m {})] (HashMap. ^Map m))
     :cljs m))

(defn fast-get
  #?@(:clj  [[^HashMap m k] (.get m k)]
      :cljs [[m k] (m k)]))

(defn strip-nils [m]
  (->> m (remove (comp nil? second)) (into {})))

#?(:clj (def +percents+ (into [] (map #(format "%%%02X" %) (range 0 256)))))

#?(:clj (defn byte->percent [^long byte]
          (nth +percents+ (if (< byte 0) (+ 256 byte) byte))))

#?(:clj (defn percent-encode [^String s]
          (->> (.getBytes s "UTF-8") (map byte->percent) (str/join))))

;;
;; encoding & decoding
;;

;; + is safe, but removed so it would work the same as with js
(defn url-encode [s]
  (if s
    #?(:clj  (str/replace s #"[^A-Za-z0-9\!'\(\)\*_~.-]+" percent-encode)
       :cljs (js/encodeURIComponent s))))

(defn maybe-url-decode [s]
  (if s
    #?(:clj  (if (.contains ^String s "%")
               (URLDecoder/decode
                (if (.contains ^String s "+")
                  (.replace ^String s "+" "%2B")
                  ^String s)
                "UTF-8"))
       :cljs (js/decodeURIComponent s))))

(defn url-decode [s]
  (or (maybe-url-decode s) s))

(defn form-encode [s]
  (if s
    #?(:clj  (URLEncoder/encode ^String s "UTF-8")
       :cljs (str/replace (js/encodeURIComponent s) "%20" "+"))))

(defn form-decode [s]
  (if s
    #?(:clj  (if (or (.contains ^String s "%") (.contains ^String s "+"))
               (URLDecoder/decode ^String s "UTF-8")
               s)
       :cljs (js/decodeURIComponent (str/replace s "+" " ")))))

(defn url-decode-coll
  "URL-decodes maps and vectors"
  [coll]
  (maybe-map-values maybe-url-decode coll))

(defprotocol IntoString
  (into-string [_]))

(extend-protocol IntoString
  #?(:clj  String
     :cljs string)
  (into-string [this] this)

  #?(:clj  clojure.lang.Keyword
     :cljs cljs.core.Keyword)
  (into-string [this]
    (let [ns (namespace this)]
      (str ns (if ns "/") (name this))))

  #?(:clj  Boolean
     :cljs boolean)
  (into-string [this] (str this))

  #?(:clj  Number
     :cljs number)
  (into-string [this] (str this))

  #?(:clj  Object
     :cljs object)
  (into-string [this] (str this))

  nil
  (into-string [_]))

(defn path-params
  "Convert parameters' values into URL-encoded strings, suitable for URL paths"
  [params]
  (maybe-map-values #(url-encode (into-string %)) params))

(defn- query-parameter [k v]
  (str (form-encode (into-string k))
       "="
       (form-encode (into-string v))))

(defn query-string
  "shallow transform of query parameters into query string"
  [params]
  (->> params
       (map (fn [[k v]]
              (if (or (sequential? v) (set? v))
                (if (seq v)
                  (str/join "&" (map query-parameter (repeat k) v))
                  ;; Empty seq results in single & character in the query string.
                  ;; Handle as empty string to behave similarly as when the value is nil.
                  (query-parameter k ""))
                (query-parameter k v))))
       (str/join "&")))
