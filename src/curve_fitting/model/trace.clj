(ns curve-fitting.model.trace
  "Functions for manipulating traces from the model."
  (:require [metaprob.builtin :as metaprob]))

;; Utility

(defn get-subtrace-in
  "`clojure.core/get-in`, but for traces. Retrieves the subtrace at the provided
  key path if it exists. Returns `nil` if any of the keys in the path are
  missing."
  [trace path]
  (if-not (seq path)
    trace
    (let [trace-key (first path)]
      (if-not (metaprob/trace-has-subtrace? trace (list trace-key))
        nil
        (get-subtrace-in (metaprob/trace-subtrace trace trace-key)
                         (rest path))))))

(def points-subtrace-path '("map"))
(def point-y-path '(1 "gaussian")) ; path from point subtrace to y choice
(def point-outlier-path '(0 "outlier-point?" "flip")) ; path from point subtrace to outlier choice
(def outliers-enabled-path '(1 "add-noise-to-curve" 0 "outliers-enabled?" "flip")) ; path from root to outliers-enabled? choice

;; Cosntructors

(defn point-subtrace
  "Generates a point subtrace that matches the values in `point`. `point` is a
  map with two optional keys: `:y` and `:outlier?`."
  [point]
  (let [{:keys [y outlier?]} point]
    (cond-> (metaprob/empty-trace)
      (some? y) (metaprob/trace-set point-y-path y)
      (some? outlier?) (metaprob/trace-set point-outlier-path outlier?))))

(defn fix-points
  "Modifies `trace` such that the choices the model makes concerning points match
  the values in `points`. See `point-subtrace` for a description of `points`."
  [trace points]
  (reduce (fn [trace [i point]]
            (metaprob/trace-set-subtrace trace
                                         (concat points-subtrace-path (list i))
                                         (point-subtrace point)))
          trace
          (zipmap (range (count points))
                  points)))

(defn points-trace
  "Returns a trace that fixes the model's outputs to `ys`."
  [ys]
  (fix-points (metaprob/empty-trace) ys))

(defn outliers-trace
  "Returns a trace that fixes the choice of whether outliers are enabled to
  `outliers?`."
  [outliers?]
  (metaprob/trace-set (metaprob/empty-trace) outliers-enabled-path outliers?))

;; Points accessors

(defn point-count
  "Returns the number of points in `trace`."
  [trace]
  (if-let [points-subtrace (get-subtrace-in trace points-subtrace-path)]
    (count (metaprob/trace-keys points-subtrace))
    0))

(defn point-subtraces
  "Returns a sequence of the point subtraces in `trace`."
  [trace]
  (map #(metaprob/trace-subtrace trace (list "map" %))
       (range (point-count trace))))

(defn point-y
  "Returns the generated `y` from the point subtrace `subtrace`."
  [subtrace]
  (when (metaprob/trace-has? subtrace point-y-path)
    (metaprob/trace-get subtrace point-y-path)))

(defn point-outlier?
  "Returns the outlier choice for the point subtrace `subtrace`."
  [subtrace]
  (when (metaprob/trace-has? subtrace point-outlier-path)
    (metaprob/trace-get subtrace point-outlier-path)))

(defn point
  "Returns the point subtrace `subtrace` as a map."
  [subtrace]
  (let [y (point-y subtrace)
        outlier? (point-outlier? subtrace)]
    (cond-> {}
      (some? y) (assoc :y y)
      (some? outlier?) (assoc :outlier? outlier?))))

(defn points
  "Returns the points in `trace` as maps."
  [trace]
  (map point (point-subtraces trace)))

(defn outliers
  "Returns each trace's notion of 'outlierness' of each point"
  [trace]
  (map point-outlier? (point-subtraces trace)))

(defn outliers-enabled?
  "Returns true if outliers are enabled in `trace`."
  [trace]
  (metaprob/trace-get
   (metaprob/trace-subtrace trace outliers-enabled-path)))

;; Polynomial

(defn degree
  "Returns the degree from a trace of the model."
  [trace]
  (metaprob/trace-get
   (metaprob/trace-subtrace
    trace
    '(1 1 "generate-curve" 0 "degree" "uniform-sample"))))

(defn coefficients
  "Returns the coefficients from `trace` in order."
  [trace]
  (let [subtrace (get-subtrace-in trace [1 1 "generate-curve" 1 "coeffs" "replicate" "map"])]
    (map #(metaprob/trace-get subtrace (list % "f" "gaussian"))
         (range (degree trace)))))

(defn coefficient-function
  [coefficients]
  (fn [x]
    (->> coefficients
         (map-indexed (fn [i coefficient]
                        (* coefficient (Math/pow x i))))
         (reduce +))))
