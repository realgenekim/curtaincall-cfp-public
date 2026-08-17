(ns cfp-scheduler-killer.views.homepage-copy
  (:require
   [cfp-scheduler-killer.views.format :as format]
   [clojure.java.io :as io]))

(def copy-resource "homepage/product-experience.md")

(defn section
  "Render editable homepage copy directly from its classpath resource.

   Intentionally resolves and reads the resource on every call: in development,
   saving the Markdown and refreshing the browser is the whole editing loop."
  []
  (let [resource (or (io/resource copy-resource)
                     (throw (ex-info "Homepage copy resource is missing"
                                     {:resource copy-resource})))]
    [:section.landing-story.homepage-product-experience
     {:data-homepage-copy copy-resource}
     (format/render-markdown (slurp resource))]))
