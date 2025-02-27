(ns re-pressed.impl
  (:require
   [re-frame.core :as rf]
   [goog.events :as ev]
   ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Vars

(def ns-root "re-pressed.core/")

(def max-record 25)

(def modifier-keys #{16 ;; shift
                     17 ;; ctrl
                     18 ;; alt
                     91 ;; windows (i.e. meta) ... TODO: apple key?
                     })



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Util

(defn ->ns-keyword [event-type]
  (fn ns-keyword
    ([]
     (ns-keyword nil))
    ([suffix]
     (keyword
      (str ns-root
           event-type
           suffix)))))


(defn is-key?
  [recent-key key-map]
  (every? (fn [[k v]]
            (= (get recent-key k)
               v))
          key-map))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subs

(defn register-subs [event-type]
  (let [ns-keyword (->ns-keyword event-type)]
    (rf/reg-sub
     (ns-keyword "-keys")
     (fn [db _]
       (get-in db [(ns-keyword) :keys])))

    (rf/reg-sub
     (ns-keyword "-event-keys")
     (fn [db _]
       (get-in db [(ns-keyword) :event-keys])))

    (rf/reg-sub
     (ns-keyword "-clear-keys")
     (fn [db _]
       (get-in db [(ns-keyword) :clear-keys])))

    (rf/reg-sub
     (ns-keyword "-always-listen-keys")
     (fn [db _]
       (get-in db [(ns-keyword) :always-listen-keys])))

    (when (= "keydown" event-type)
      (rf/reg-sub
       (ns-keyword "-prevent-default-keys")
       (fn [db _]
         (get-in db [(ns-keyword) :prevent-default-keys]))))
    ))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events

(defn- add-key
  [current-key-maps new-key-map]
  (let [keys (if new-key-map
               (conj (or current-key-maps
                         [])
                     new-key-map)
               current-key-maps)]
    (into []
          (take-last max-record keys))))


(defn- ->set-key!
  [event-type]
  (let [ns-keyword (->ns-keyword event-type)]
    (fn set-key!
      [{:keys [db]} [_ key-map]]
      {:db (update-in db [(ns-keyword) :keys] add-key key-map)})))


(defn- ->clear-keys!
  [event-type]
  (let [ns-keyword (->ns-keyword event-type)]
    (fn clear-keys!
      [{:keys [db]} [_ key-map]]
      {:db (assoc-in db [(ns-keyword)
                         :keys] [])})))


(defn prevent-default-keys!
  [_ [_ e]]
  (let [k (keyword
           (str ns-root "prevent-default"))]
    {k e}))


(defn register-events [event-type]
  (let [ns-keyword (->ns-keyword event-type)]

    (rf/reg-event-fx  (ns-keyword "-set-key")
                      (->set-key! event-type))

    (rf/reg-event-fx (ns-keyword "-clear-keys")
                     (->clear-keys! event-type))

    (when (= "keydown" event-type)
      (rf/reg-event-fx (ns-keyword "-prevent-default-keys")
                       prevent-default-keys!))))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Effects

(defn register-effects [event-type]
  (when (= "keydown" event-type)
    (rf/reg-fx
     (keyword
      (str ns-root "prevent-default"))
     (fn [e]
       (.preventDefault e)))))


(rf/reg-fx
  (keyword
    (str ns-root "keyboard-event"))

  (fn [{:keys [event-type] :as keyboard-event-args}]
    (register-subs event-type)
    (register-events event-type)
    (register-effects event-type)

    (ev/listen
      js/document
      event-type
      (fn [e]
        (rf/dispatch-sync [(keyword
                             (str ns-root "handle-keyboard-event"))
                           keyboard-event-args
                           e])))))


(rf/reg-event-fx
  (keyword
    (str ns-root "handle-keyboard-event"))

  (fn [{:keys [db]}
       [_
        {:keys [event-type]
         {:keys [clear-on-success-event-match]} :arguments}
        e]]
    (let [ns-keyword (->ns-keyword event-type)

          e-key         (.-keyCode e)
          modifier-key? (modifier-keys e-key)

          ;; --
          ;; thanks re-frame-10x for this snippet
          tag-name        (.-tagName (.-target e))
          entering-input? (contains?
                            #{"INPUT"
                              "SELECT"
                              "TEXTAREA"} tag-name)
          ;; --

          hit-key {:altKey   (.-altKey e)
                   :ctrlKey  (.-ctrlKey e)
                   :metaKey  (.-metaKey e)
                   :shiftKey (.-shiftKey e)
                   :keyCode  (.-keyCode e)}

          {recent-keys :keys
           :keys [always-listen-keys
                  prevent-default-keys
                  clear-keys
                  event-keys]} (get db (ns-keyword))

          always-listen?     (some #(is-key? hit-key %) always-listen-keys)]

      (when (or (and (not modifier-key?)
                     (not entering-input?))
                always-listen?)
        (let [recent-keys (add-key recent-keys hit-key)
              recent-key (last recent-keys)

              is-key-sequence? (fn [key-maps]
                                 (every? true?
                                         (mapv
                                           #(is-key? %1 %2)
                                           (concat (reverse recent-keys)
                                                   (repeat max-record {}))
                                           (reverse key-maps))))

              check-events (doall
                             (for [[trigger-event
                                    & ks] event-keys]
                               (let [triggered? (some is-key-sequence? ks)]
                                 (vector trigger-event triggered?))))

              [triggered-event event?] (some->> check-events
                                                (filter #(true? (second %)))
                                                first)

              clear? (some is-key-sequence? clear-keys)]
          {:db (assoc-in db [(ns-keyword) :keys]
                         (if (or clear?
                                 (and event? clear-on-success-event-match))
                           []
                           recent-keys))
           :fx
           (cond-> []
             (and (= "keydown" event-type)
                  (some #(is-key? recent-key %) prevent-default-keys))
             (conj [:dispatch [(ns-keyword "-prevent-default-keys") e]])

             event?
             (conj [:dispatch (conj triggered-event
                                    e
                                    recent-keys)]))})))))
