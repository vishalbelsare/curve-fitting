(ns curve-fitting.sketches
  (:require [quil.core :as quil]
            [quil.applet :as applet]
            [quil.middleware :as middleware]
            [curve-fitting.core :as core]
            [curve-fitting.db :as db]
            [curve-fitting.sketches.prior :as prior]
            [curve-fitting.sketches.resampling :as resampling]))

(defn mouse-pressed
  [state x-scale y-scale event]
  (let [{:keys [x y]} event]
    (db/add-point state [(x-scale x) (y-scale y)])))

(defn key-typed
  [state {:keys [raw-key] :as event}]
  (cond (= raw-key \c)
        (db/init)

        (= raw-key \t)
        (db/toggle-mode state)

        (contains? #{\0 \1 \2 \3 \4 \5 \6 \7 \8 \9} raw-key)
        (db/add-digit state raw-key)

        (= raw-key \backspace)
        (db/delete-digit state)

        (= raw-key \newline)
        (db/set-max-curves state)

        :else (db/clear-curves state)))

(defn applet
  [{:keys [state pixel-width pixel-height x-scale y-scale anti-aliasing make-opacity-scale max-curves]}]
  (applet/applet :size [pixel-width pixel-height]
                 :draw (fn [_] (core/draw! @state x-scale y-scale pixel-width pixel-height make-opacity-scale))
                 :mouse-pressed (fn [_ event] (swap! state #(mouse-pressed % x-scale y-scale event)))
                 :key-typed (fn [_ event] (swap! state #(key-typed % event)))
                 ;; Why :no-bind-output is necessary: https://github.com/quil/quil/issues/216
                 :features [:keep-on-top :no-bind-output]
                 ;; Maybe we don't need this any more?
                 :middleware [#'middleware/fun-mode]
                 :settings #(quil/smooth anti-aliasing)))

(defn sampling-thread
  [stop? state num-particles]
  (future
    (try
      (loop []
        (when-not @stop?
          (let [{old-points :points, old-mode :mode :as old-val} @state
                curve (case old-mode
                        :resampling (resampling/sample-curve old-points num-particles)
                        :prior      (prior/sample-curve old-points))]
            (swap! state (fn [{new-points :points
                               new-mode :mode
                               curves :curves
                               max-curves :max-curves :as new-val}]
                           ;; Discard the curve if the points changed while
                           ;; we were working.
                           (cond-> new-val
                             (and (= new-points old-points)
                                  (< (count curves) max-curves)
                                  (= old-mode new-mode))
                             (db/add-curve curve)))))
          (recur)))
      (catch Exception e
        (.printStackTrace e)))))
