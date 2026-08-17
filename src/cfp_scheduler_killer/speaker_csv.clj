(ns cfp-scheduler-killer.speaker-csv
  "Pure CSV decoding and validation for speaker imports. No store or I/O."
  (:require
   [cfp-scheduler-killer.domain.speakers :as speaker-domain]
   [cfp-scheduler-killer.submissions :as submissions]
   [clojure.string :as str]))

(def accepted-columns
  "Canonical values accepted by the speaker CSV importer.

   Each entry is [value-key preview-label canonical-header]. Keeping this list
   next to the parser gives the preview and its contract test one family-level
   source of truth when another accepted column is added."
  [[:name "Name" "Name"]
   [:email "Email" "Email"]
   [:status "Status" "Status"]
   [:title "Title" "Job Title"]
   [:organization "Organization" "Company"]
   [:bio "Bio" "Bio"]
   [:headshot-url "Headshot URL" "Photo URL"]
   [:location "Location" "Location"]
   [:notes "Notes" "Notes"]])

(def header-aliases
  {"email" :email
   "email address" :email
   "speaker email" :email
   "name" :name
   "speaker name" :name
   "first name" :first-name
   "last name" :last-name
   "title" :title
   "job title" :title
   "company" :organization
   "company name" :organization
   "organization" :organization
   "organisation" :organization
   "status" :status
   "bio" :bio
   "biography" :bio
   "headshot url" :headshot-url
   "photo url" :headshot-url
   "location" :location
   "notes" :notes})

(def email-pattern #"(?i)^[^\s@]+@[^\s@]+\.[^\s@]+$")

(defn- finish-field [rows row field]
  [rows (conj row (str field)) (StringBuilder.)])

(defn rows
  "RFC-4180-shaped decoder: commas/newlines inside quotes and doubled quotes
   are preserved. Returns vectors of strings; validation is a separate step."
  [text]
  (loop [chars (seq (str text))
         parsed []
         row []
         field (StringBuilder.)
         quoted? false]
    (if-let [ch (first chars)]
      (let [next-ch (second chars)]
        (cond
          (and quoted? (= ch (char 34)) (= next-ch (char 34)))
          (do (.append field (char 34))
              (recur (nnext chars) parsed row field true))

          (= ch (char 34))
          (recur (next chars) parsed row field (not quoted?))

          (and (not quoted?) (= ch \,))
          (let [[parsed row field] (finish-field parsed row field)]
            (recur (next chars) parsed row field false))

          (and (not quoted?) (or (= ch \newline) (= ch \return)))
          (let [[_ row field] (finish-field parsed row field)
                remaining (if (and (= ch \return) (= next-ch \newline))
                            (nnext chars)
                            (next chars))]
            (recur remaining (conj parsed row) [] field false))

          :else
          (do (.append field ch)
              (recur (next chars) parsed row field quoted?))))
      (let [[_ row _] (finish-field parsed row field)]
        (cond-> parsed
          (some (complement str/blank?) row) (conj row))))))

(defn- normalized-header [s]
  (-> s str (str/replace #"^\uFEFF" "") str/trim str/lower-case
      (str/replace #"[_-]+" " ")
      (str/replace #"\s+" " ")))

(defn- row-map [headers cells]
  (reduce-kv (fn [m idx header]
               (if-let [k (get header-aliases (normalized-header header))]
                 (assoc m k (some-> (get cells idx "") str/trim not-empty))
                 m))
             {}
             headers))

(defn- complete-name [{:keys [name first-name last-name] :as row}]
  (assoc row :name (or name
                       (not-empty (str/trim (str (or first-name "") " "
                                                 (or last-name "")))))))

(defn- errors-for [{:keys [email name status headshot-url]}]
  (cond-> {}
    (str/blank? (str email)) (assoc :email "Email is required")
    (and email (not (re-matches email-pattern email)))
    (assoc :email "Enter a valid email address")
    (str/blank? (str name)) (assoc :name "Name is required")
    (and status (not (contains? speaker-domain/statuses status)))
    (assoc :status "Status must be Invited, Confirmed, Submitted, or Withdrawn")
    (and headshot-url (not (submissions/valid-headshot-url? headshot-url)))
    (assoc :headshot-url "Headshot URL must be a complete http:// or https:// URL")))

(defn parse
  "Decode, normalize common fixture/header variants, and attach row errors.
   Duplicate email rows are refused rather than silently picking a winner."
  [text]
  (let [[headers & body] (rows text)
        recognized (set (keep #(get header-aliases (normalized-header %)) headers))
        present-columns (cond-> (disj recognized :first-name :last-name)
                          (or (contains? recognized :first-name)
                              (contains? recognized :last-name))
                          (conj :name))
        missing-headers (cond-> []
                          (not (contains? recognized :email)) (conj "email")
                          (not (or (contains? recognized :name)
                                   (contains? recognized :first-name))) (conj "name"))
        prepared (map-indexed
                   (fn [idx cells]
                     (let [values (-> (row-map headers cells)
                                      complete-name
                                      (update :email speaker-domain/normalize-email)
                                      (update :status #(or % "Invited"))
                                      (dissoc :first-name :last-name))]
                       {:row-number (+ idx 2)
                        :values values
                        :errors (errors-for values)}))
                   (remove #(every? str/blank? %) body))
        frequencies (frequencies (keep #(get-in % [:values :email]) prepared))
        parsed (mapv (fn [row]
                       (let [email (get-in row [:values :email])]
                         (if (> (get frequencies email 0) 1)
                           (assoc-in row [:errors :email]
                                     "Duplicate email in this file")
                           row)))
                     prepared)]
    {:headers headers
     :present-columns present-columns
     :missing-headers missing-headers
     :rows parsed
     :valid-count (count (remove (comp seq :errors) parsed))
     :error-count (count (filter (comp seq :errors) parsed))
     :valid? (and (empty? missing-headers)
                  (every? (comp empty? :errors) parsed))}))
