(ns cfp-scheduler-killer.og-card-test
  (:require
   [cfp-scheduler-killer.og-card :as og-card]
   [clojure.test :refer [deftest is testing]])
  (:import
   (java.awt Color)
   (java.io ByteArrayInputStream)
   (java.time LocalDate)
   (javax.imageio ImageIO)))

(deftest render-card-produces-a-populated-1200-by-630-png
  (let [event {:id "event-1"
               :name "Enterprise AI Summit Charlotte"
               :location "Charlotte, NC"
               :starts-on (LocalDate/of 2026 10 14)
               :ends-on (LocalDate/of 2026 10 15)}
        speaker {:id "speaker-1"
                 :name "Ada Lovelace"
                 :tagline "Computing pioneer"
                 :company "Analytical Engines"
                 :sessions [{:title "The Poetry of Analytical Engines"}]}
        png-bytes (og-card/render-card event speaker nil)
        image (ImageIO/read (ByteArrayInputStream. png-bytes))
        png-signature (mapv #(bit-and (int %) 0xff) (take 8 png-bytes))
        background-rgb (.getRGB (Color/decode "#FAF7F2"))
        name-band-non-background
        (count
          (for [y (range 105 220)
                x (range 65 730)
                :when (not= background-rgb (.getRGB image x y))]
            true))]
    (testing "the output is a readable PNG at the Open Graph dimensions"
      (is (= [137 80 78 71 13 10 26 10] png-signature))
      (is (some? image))
      (is (= 1200 (.getWidth image)))
      (is (= 630 (.getHeight image))))

    (testing "the speaker name visibly changes a substantial part of its band"
      (is (> name-band-non-background 500)))))
(deftest title-layout-preserves-the-complete-mik-title
  (let [title "From Project to Product: Flow Metrics for the AI-Native Enterprise"
        full-text (str "“" title "”")
        image (java.awt.image.BufferedImage.
                1200 630 java.awt.image.BufferedImage/TYPE_INT_RGB)
        graphics (.createGraphics image)]
    (try
      (let [{:keys [lines complete?]}
            (#'og-card/fit-title-layout graphics title)]
        (is complete?)
        (is (= full-text (apply str (interpose " " lines))))
        (is (not-any? #(.contains ^String % "…") lines)))
      (finally
        (.dispose graphics)))))

(deftest render-event-card-meets-the-actual-big-card-byte-contract
  (let [event {:name "Enterprise AI Summit Charlotte"
               :location "Charlotte, NC"
               :speaker-count 14
               :cfp-intro "Stories from people building enterprise AI."
               :settings {:hero-image-url "/images/eais-charlotte-hero.jpg"}}
        copy (og-card/event-card-copy event)
        bytes (og-card/render-event-card event)
        image (ImageIO/read (ByteArrayInputStream. bytes))]
    (is (>= (.getWidth image) 1200))
    (is (>= (.getHeight image) 627))
    (is (= "CURTAIN CALL · EVENT" (:kicker copy)))
    (is (re-find #"14 announced speakers" (:subtitle copy)))
    (is (not-any? #(re-find #"I'M SPEAKING|Speaker|“" (str %)) (vals copy)))
    (is (>= (:width og-card/event-hero-box) 900))
    (is (>= (:height og-card/event-hero-box) 330))))

(deftest render-homepage-card-meets-the-actual-big-card-byte-contract
  (let [bytes (og-card/render-homepage-card)
        image (ImageIO/read (ByteArrayInputStream. bytes))]
    (is (>= (.getWidth image) 1200))
    (is (>= (.getHeight image) 627))))
