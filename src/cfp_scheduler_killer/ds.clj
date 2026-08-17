(ns cfp-scheduler-killer.ds
  "Datastar expression helpers — safe signal arithmetic + keydown builders.

   Datastar parses $signal-1 as camelCase signal name $signal1, NOT subtraction.
   These helpers generate correctly-parenthesized expressions that prevent this bug.

   Usage:
     (require '[cfp-scheduler-killer.ds :as ds])
     ;; In Hiccup:
     {:data-star-on:keydown__window
      (ds/keydown-expr
        [(ds/on-meta \"z\" \"doUndo()\")]
        [(ds/on-key \"j\" {} (ds/signal-inc \"$idx\" 27))
         (ds/on-key \"k\" {} (ds/signal-dec \"$idx\"))])}

   See also: datastar-sse skill for full SSE + keyboard navigation patterns."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Signal arithmetic — prevents the $foo-1 camelCase parsing bug
;; ---------------------------------------------------------------------------

(defn signal-inc
  "Increment a signal, clamped to max-val.
   (signal-inc \"$dIdx\" 27) => \"$dIdx=Math.min(($dIdx) + 1, 27)\""
  [signal max-val]
  (str signal "=Math.min((" signal ") + 1," max-val ")"))

(defn signal-dec
  "Decrement a signal, clamped to 0.
   (signal-dec \"$dIdx\") => \"$dIdx=Math.max(($dIdx) - 1, 0)\""
  [signal]
  (str signal "=Math.max((" signal ") - 1, 0)"))

(defn signal-set
  "Set a signal to a value.
   (signal-set \"$dCol\" \"'left'\") => \"$dCol='left'\""
  [signal value]
  (str signal "=" value))

(defn signal-clamp
  "Clamp a signal to [0, max-val].
   (signal-clamp \"$dIdx\" 27) => \"$dIdx=Math.min($dIdx, 27)\""
  [signal max-val]
  (str signal "=Math.min(" signal "," max-val ")"))

;; ---------------------------------------------------------------------------
;; Keydown expression builder
;; ---------------------------------------------------------------------------

(defn on-key
  "Build a guarded keydown clause for a plain or shift+key combination.
   (on-key \"j\" {} (signal-inc \"$idx\" 27))
   => \"if(evt.key==='j'){evt.preventDefault();$idx=Math.min(($idx) + 1, 27)}\"

   (on-key \"J\" {:shift true} \"reorderDown()\")
   => \"if(evt.shiftKey&&evt.key==='J'){evt.preventDefault();reorderDown()}\""
  [key {:keys [shift]} & body-strs]
  (str "if("
       (when shift "evt.shiftKey&&")
       "evt.key==='" key "'"
       "){evt.preventDefault();"
       (apply str body-strs)
       "}"))

(defn guard-input
  "Skip keydown when focus is in a text input.
   Prepend to data-star-on:keydown expressions.
   NOTE: prefer `keydown-expr` which handles ordering automatically."
  []
  "if(evt.target.tagName==='INPUT'||evt.target.tagName==='TEXTAREA')return;")

;; Mac Alt/Option key produces dead characters (≈ for Alt+X, ∂ for Alt+D, etc.)
;; Always use evt.code for Alt+ shortcuts, evt.key for plain/shift/meta keys.
(def ^:private mac-alt-codes
  "Map of logical key name to evt.code value, for Alt+ shortcuts on Mac."
  {"x" "KeyX" "z" "KeyZ" "d" "KeyD" "s" "KeyS" "c" "KeyC" "v" "KeyV"
   "a" "KeyA" "e" "KeyE" "f" "KeyF" "k" "KeyK" "n" "KeyN" "p" "KeyP"
   "r" "KeyR" "t" "KeyT" "w" "KeyW" "q" "KeyQ" "o" "KeyO" "i" "KeyI"
   "1" "Digit1" "2" "Digit2" "3" "Digit3" "4" "Digit4" "5" "Digit5"})

(defn on-meta
  "Keydown clause for Cmd/Ctrl shortcuts.  Uses evt.key.
   (on-meta \"z\" \"doUndo()\") => if((evt.metaKey||evt.ctrlKey)&&evt.key==='z'){...}"
  [key & body-strs]
  (str "if((evt.metaKey||evt.ctrlKey)&&evt.key==='" key "'){evt.preventDefault();"
       (apply str body-strs) ";return}"))

(defn on-alt
  "Keydown clause for Alt/Option shortcuts.  Uses evt.code for Mac safety.
   (on-alt \"x\" \"clearAI()\") => if(evt.altKey&&evt.code==='KeyX'){...}"
  [key & body-strs]
  (let [code (get mac-alt-codes key (str "Key" (str/upper-case key)))]
    (str "if(evt.altKey&&evt.code==='" code "'){evt.preventDefault();"
         (apply str body-strs) ";return}")))

(defn keydown-expr
  "Build a complete keydown expression with correct ordering:
   1. global-strs  — always fire (meta/alt shortcuts, work in textareas)
   2. guard-input  — skip rest if focus is in INPUT/TEXTAREA
   3. nav-strs     — only fire outside text inputs (j/k/h/l, d, s, etc.)

   (keydown-expr
     [(on-meta \"z\" undo-action) (on-alt \"x\" clear-action)]
     [(on-key \"j\" {} nav-down) (on-key \"k\" {} nav-up)])"
  [global-strs nav-strs]
  (apply str (concat global-strs [(guard-input)] nav-strs)))

;; ---------------------------------------------------------------------------
;; Server action helpers — inline fetch() calls for Datastar expressions
;; ---------------------------------------------------------------------------

(defn post-action
  "Generate an inline fetch() POST call for Datastar on:click expressions.
   Payload-map values are JS expressions (unquoted).
   (post-action \"/api/move\" {:idx \"$dIdx\"})
   => fetch('/api/move', {method:'POST', body:JSON.stringify({idx:$dIdx})})"
  [endpoint payload-map]
  (let [pairs (str/join "," (map (fn [[k v]] (str (name k) ":" v)) payload-map))
        opts  (str "{method:'POST',headers:{'Content-Type':'application/json'},"
                   "body:JSON.stringify({" pairs "})}")
        catch ".catch(e=>console.error(e))"]
    (str "fetch('" endpoint "'," opts ")" catch)))
