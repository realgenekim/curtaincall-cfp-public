(ns cfp-scheduler-killer.views.compact-person)

(defn person-link
  "The compact face + name identity used by dense organizer ledgers.

   Callers own the surrounding table cell and may supply one small secondary
   line. A nil image intentionally renders the anonymous-review avatar."
  [{:keys [href link-class image-src image-class image-alt name name-class secondary]}]
  [:a.lg-person-link
   (cond-> {:href href}
     link-class (assoc :class link-class))
   (if image-src
     [:img.b-face
      (cond-> {:src image-src :alt (or image-alt name)}
        image-class (assoc :class image-class))]
     [:span.b-face.blind-avatar {:aria-hidden "true"} "?"])
   [:div.lg-pwho
    [:div.lg-pname
     (cond-> {}
       name-class (assoc :class name-class))
     name]
    secondary]])
