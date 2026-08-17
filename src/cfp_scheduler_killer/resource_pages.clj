(ns cfp-scheduler-killer.resource-pages
  (:require
   [cfp-scheduler-killer.events :as events]
   [cfp-scheduler-killer.store :as store]
   [clojure.string :as str]))

(defn pages-for-event [event]
  (->> (vals (:resource-pages (store/snapshot)))
       (filter #(= (:id event) (:event-id %)))
       (sort-by (juxt :title :id))
       vec))

(defn published-pages [event]
  (filterv :published? (pages-for-event event)))

(defn page-by-slug [event slug]
  (some #(when (= slug (:slug %)) %) (pages-for-event event)))

(defn published-page-by-slug [event slug]
  (some #(when (and (:published? %) (= slug (:slug %))) %)
        (pages-for-event event)))

(defn- clean-page [page]
  (let [title (some-> (:title page) str/trim)
        body (some-> (:body page) str/trim)
        slug (or (some-> (:slug page) str/trim not-empty)
                 (events/slugify title))]
    (cond
      (str/blank? title) {:error "A resource page needs a title."}
      (str/blank? body) {:error "A resource page needs a body."}
      (str/blank? slug) {:error "A resource page needs a usable URL slug."}
      :else {:page (assoc page :title title :body body :slug slug)})))

(defn save-page! [event page actor]
  (let [{:keys [page error]} (clean-page page)
        existing (when (:id page) (some #(when (= (:id page) (:id %)) %)
                                       (pages-for-event event)))
        duplicate (some #(when (and (= (:slug page) (:slug %))
                                    (not= (:id page) (:id %))) %)
                        (pages-for-event event))]
    (cond
      error {:error error}
      (and (:id page) (nil? existing)) {:error "That resource page no longer exists."}
      duplicate {:error "That URL slug is already used by another resource page."}
      :else
      (let [saved (merge existing page
                         {:id (or (:id page) (store/new-id))
                          :event-id (:id event)
                          :published? (boolean (:published? page))
                          :updated-at (store/now-iso)})]
        (store/append! {:type "resource-page.saved"
                        :actor actor
                        :event-id (:id event)
                        :payload {:page saved}})
        {:page saved}))))
