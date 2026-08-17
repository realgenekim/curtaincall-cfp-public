(ns cfp-scheduler-killer.review-scores-csv
  "Deterministic, tenant-scoped review-score export for organizers."
  (:require
   [cfp-scheduler-killer.review-plan :as review-plan]
   [cfp-scheduler-killer.reviews :as reviews]
   [cfp-scheduler-killer.store :as store]
   [cfp-scheduler-killer.submissions :as submissions]
   [clojure.data.json :as json]
   [clojure.string :as str]))

(def header
  ["submission_id" "title" "speaker_name" "speaker_email"
   "decision_status" "content_status" "priority" "review_count"
   "mean_stars" "reviewer_name" "reviewer_id" "stars" "rated_at"])

(defn- csv-cell [value]
  (let [s (str (or value ""))]
    (if (re-find #"[\",\r\n]" s)
      (str "\"" (str/replace s "\"" "\"\"") "\"")
      s)))

(defn- score [value]
  (when (some? value)
    (format "%.1f" (double value))))

(defn- recused?
  [state submission-id person-id]
  (boolean (get-in state [:review-recusals [submission-id person-id]])))

(defn- eligible-ratings
  [state submission]
  (->> (:ratings submission)
       (remove #(recused? state (:id submission) (:person-id %)))
       vec))

(defn- submission-base [submission ratings]
  (let [snapshot-speaker (some-> submission :speakers first)
        person (some-> snapshot-speaker :person-id store/person-by-id)
        speaker (merge snapshot-speaker (select-keys person [:name :email]))
        stars (map :stars ratings)]
    [(str (:id submission))
     (submissions/title-of submission)
     (:name speaker)
     (:email speaker)
     (:status submission)
     (submissions/content-status submission)
     (boolean (:priority submission))
     (count ratings)
     (score (reviews/mean stars))]))

(defn- reviewer-name
  [state person-id rating]
  (or (:person-name rating)
      (get-in state [:people person-id :name])
      (str person-id)))

(defn rows
  "One deterministic row per eligible reviewer's current signed Stars.

   Active recusal removes the reviewer from counts and rows while preserving
   facts in the append-only store. Unrated submissions still get one row."
  [event]
  (let [state (store/snapshot)]
    (->> (reviews/enriched-for-event (:id event))
         (sort-by (juxt #(str/lower-case (str (submissions/title-of %)))
                        #(str (:id %))))
         (mapcat
           (fn [submission]
             (let [ratings (eligible-ratings state submission)
                   rating-by-person-id (into {} (map (juxt :person-id identity)) ratings)
                   person-ids (sort-by
                                (fn [person-id]
                                  [(str/lower-case
                                     (reviewer-name state person-id
                                                    (get rating-by-person-id person-id)))
                                   (str person-id)])
                                (keys rating-by-person-id))
                   base (submission-base submission ratings)]
               (if (seq person-ids)
                 (map (fn [person-id]
                        (let [rating (get rating-by-person-id person-id)]
                          (into base
                                [(reviewer-name state person-id rating)
                                 (str person-id)
                                 (score (:stars rating))
                                 (:at rating)])))
                      person-ids)
                 [(into base [nil nil nil nil])]))))
         vec)))

(defn render
  "RFC 4180-style CSV with CRLF line endings and a stable final newline."
  [event]
  (str (->> (cons header (rows event))
            (map #(str/join "," (map csv-cell %)))
            (str/join "\r\n"))
       "\r\n"))

(def review-results-header
  ["submission_id" "title" "speaker_name" "speaker_email"
   "decision_status" "content_status" "priority" "review_count"
   "mean_stars" "presenter_visibility" "ratings_json" "comments_json"])

(defn- presenter [submission visible?]
  (if visible?
    (let [snapshot-speaker (some-> submission :speakers first)
          person (some-> snapshot-speaker :person-id store/person-by-id)
          speaker (merge snapshot-speaker (select-keys person [:name :email]))]
      {:speakerName (:name speaker)
       :speakerEmail (:email speaker)})
    {:speakerName "Anonymous speaker"
     :speakerEmail nil}))

(defn- reviewer-sort-key [entry]
  [(str/lower-case (str (:person-name entry)))
   (str (:person-id entry))
   (str (:at entry))])

(defn- rating-result [state rating]
  {:reviewerId (str (:person-id rating))
   :reviewerName (reviewer-name state (:person-id rating) rating)
   :stars (:stars rating)
   :ratedAt (some-> (:at rating) str)})

(defn- comment-result [state comment]
  {:commentId (str (:id comment))
   :reviewerId (str (:person-id comment))
   :reviewerName (reviewer-name state (:person-id comment) comment)
   :body (:body comment)
   :commentedAt (some-> (:at comment) str)})

(defn- submission-result [state submission visible?]
  (let [ratings (->> (eligible-ratings state submission)
                     (sort-by reviewer-sort-key)
                     vec)
        comments (->> (:comments submission)
                      (remove #(recused? state (:id submission) (:person-id %)))
                      (sort-by reviewer-sort-key)
                      vec)
        stars (map :stars ratings)]
    (merge
      {:submissionId (str (:id submission))
       :title (submissions/title-of submission)
       :decisionStatus (:status submission)
       :contentStatus (submissions/content-status submission)
       :priority (boolean (:priority submission))
       :reviewCount (count ratings)
       :meanStars (score (reviews/mean stars))
       :ratings (mapv #(rating-result state %) ratings)
       :comments (mapv #(comment-result state %) comments)}
      (presenter submission visible?))))

(defn review-results-data
  "One deterministic nested record per submission, with presenter visibility applied."
  [event]
  (let [state (store/snapshot)
        visibility (review-plan/presenter-visibility-policy (:id event))
        visible? (contains? #{"visible" :visible} (:mode visibility))
        results (->> (reviews/enriched-for-event (:id event))
                     (sort-by (juxt #(str/lower-case (str (submissions/title-of %)))
                                    #(str (:id %))))
                     (mapv #(submission-result state % visible?)))]
    {:eventId (str (:id event))
     :eventSlug (:slug event)
     :eventName (:name event)
     :presenterVisibility (:mode visibility)
     :totalSubmissions (count results)
     :submissions results}))

(defn review-results-rows [event]
  (let [{:keys [presenterVisibility submissions]} (review-results-data event)]
    (mapv (fn [submission]
            [(:submissionId submission)
             (:title submission)
             (:speakerName submission)
             (:speakerEmail submission)
             (:decisionStatus submission)
             (:contentStatus submission)
             (:priority submission)
             (:reviewCount submission)
             (:meanStars submission)
             presenterVisibility
             (json/write-str (:ratings submission))
             (json/write-str (:comments submission))])
          submissions)))

(defn render-review-results
  "RFC 4180 CSV with one submission per row and nested review data in JSON cells."
  [event]
  (str (->> (cons review-results-header (review-results-rows event))
            (map #(str/join "," (map csv-cell %)))
            (str/join "\r\n"))
       "\r\n"))
