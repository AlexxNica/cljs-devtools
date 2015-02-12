(ns devtools.format)

(def max-coll-elements 5)
(def more-marker "…")
(def line-index-separator ":")
(def dq "\"")
(def surrogate-key "$$surrogate")
(def standard-ol-style "list-style-type:none; padding-left:0px; margin-top:0px; margin-bottom:0px; margin-left:12px")
(def standard-li-style "margin-left:12px")
(def spacer " ")
(def span "span")
(def ol "ol")
(def li "li")
(def general-cljs-land-style "background-color:#efe")

; IRC #clojurescript @ freenode.net on 2015-01-27:
; [13:40:09] darwin_: Hi, what is the best way to test if I'm handled ClojureScript data value or plain javascript object?
; [14:04:34] dnolen: there is a very low level thing you can check
; [14:04:36] dnolen: https://github.com/clojure/clojurescript/blob/c2550c4fdc94178a7957497e2bfde54e5600c457/src/clj/cljs/core.clj#L901
; [14:05:00] dnolen: this property is unlikely to change - still it's probably not something anything anyone should use w/o a really good reason
(defn cljs-value? [value]
  (try
    (exists? (aget value "constructor" "cljs$lang$type"))
    (catch js/Object _
      false)))

(defn js-value? [value]
  (not (cljs-value? value)))

(defn surrogate? [value]
  (exists? (aget value surrogate-key)))

(defn template [tag style & children]
  (let [js-array #js [tag (if (empty? style) #js {} #js {"style" style})]]
    (doseq [child children]
      (if (coll? child)
        (.apply (aget js-array "push") js-array (into-array child)) ; convenience helper to splat cljs collections
        (.push js-array child)))
    js-array))

(defn reference [object & children]
  (let [js-array #js ["object" #js {"object" object}]]
    (doseq [child children]
      (.push js-array child))
    js-array))

(defn surrogate
  ([object header] (surrogate object header true))
  ([object header has-body] (js-obj
                              surrogate-key true
                              "target" object
                              "header" header
                              "hasBody" has-body)))

(defn wrap-cljs-if-needed [needed? tmpl]
  (if needed?
    (template span general-cljs-land-style tmpl)
    tmpl))

(defn index-template [value]
  (template span "color:#881391" value line-index-separator))

(defn nil-template [_]
  (template span "color:#808080" "nil"))

(defn keyword-template [value]
  (template span "color:#881391" (str ":" (name value))))

(defn symbol-template [value]
  (template span "color:#000000" (str value)))

(defn number-template [value]
  (if (integer? value)
    (template span "color:#1C00CF" value)
    (template span "color:#1C88CF" value)))

; TODO: abbreviate long strings
(defn string-template [value]
  (template span "color:#C41A16" (str dq value dq)))

(defn fn-template [value]
  (template span "color:#090" (reference (surrogate value "λ"))))

(defn bool-template [value]
  (template span "color:#099" value))

(defn bool? [value]
  (or (true? value) (false? value)))

(defn atomic-template [value]
  (cond
    (nil? value) (nil-template value)
    (bool? value) (bool-template value)
    (string? value) (string-template value)
    (number? value) (number-template value)
    (keyword? value) (keyword-template value)
    (symbol? value) (symbol-template value)
    (fn? value) (fn-template value)))

(defn abbreviated? [template]
  (some #(= more-marker %) template))

(deftype TemplateWriter [t]
  Object
  (merge [_ a] (.apply (.-push t) t a))
  IWriter
  (-write [_ o] (.push t o))
  (-flush [_] nil))

(defn wrap-group-in-cljs-if-needed [group obj]
  (if (cljs-value? obj)
    #js [(.concat (template span general-cljs-land-style) group)]
    group))

(defn wrap-group-in-reference-if-needed [group obj]
  (if (abbreviated? group)
    #js [(reference (surrogate obj (.concat (template span "") group)))]
    group))

; default printer implementation can do this:
;   :else (write-all writer "#<" (str obj) ">")
; we want to wrap stringified obj in a reference for further inspection
(defn detect-else-case-and-patch-it [group obj]
  (if (and (= (count group) 3) (= (aget group 0) "#<") (= (str obj) (aget group 1)) (= (aget group 2) ">"))
    (aset group 1 (reference (surrogate obj (aget group 1)))))) ; TODO change to direct reference after devtools guys fix the bug

(defn alt-printer-impl [obj writer opts]
  (if-let [tmpl (atomic-template obj)]
    (-write writer tmpl)
    (let [inner-tmpl #js []
          inner-writer (TemplateWriter. inner-tmpl)
          default-impl (:fallback-impl opts)]
      ; we want to limit print-level, at second level use maximal abbreviation e.g. [...] or {...}
      (if (= *print-level* 1)
        (default-impl obj inner-writer (assoc opts :print-length 0))
        (default-impl obj inner-writer opts))
      (detect-else-case-and-patch-it inner-tmpl obj)        ; an ugly special case
      (.merge writer (wrap-group-in-cljs-if-needed (wrap-group-in-reference-if-needed inner-tmpl obj) obj)))))

(defn managed-pr-str [value print-level]
  (let [tmpl (template span "")
        writer (TemplateWriter. tmpl)]
    (binding [*print-level* print-level]                    ; when printing do at most print-level deep recursion
      (pr-seq-writer [value] writer {:alt-impl     alt-printer-impl
                                     :print-length max-coll-elements
                                     :more-marker  more-marker}))
    (wrap-cljs-if-needed (cljs-value? value) tmpl)))

(defn build-header [value]
  (managed-pr-str value 2))

(defn standard-body-template [lines]
  (template ol standard-ol-style (map #(template li standard-li-style %) lines)))

(defn body-line-template [index value]
  [(index-template index) spacer (managed-pr-str value 3)])

(defn body-lines-templates [value]
  (loop [data (take 100 (seq value))                        ; TODO: generate "more" links for continuation
         index 0
         lines []]
    (if (empty? data)
      lines
      (recur (rest data) (inc index) (conj lines (body-line-template index (first data)))))))

(defn build-body [value]
  (standard-body-template (body-lines-templates value)))

(defn build-surrogate-body [value]
  (let [target (.-target value)]
    (if (seqable? target)
      (build-body target)
      (template ol standard-ol-style (template li standard-li-style (reference target (str target)))))))

(defn want-value? [value]
  (or (cljs-value? value)
    (surrogate? value)))

;;;;;;;;; PROTOCOL SUPPORT

(defprotocol IDevtoolsFormat
  (-header [value])
  (-has-body [value])
  (-body [value]))

;;;;;;;;; API CALLS

(defn header-api-call [value]
  (cond
    (satisfies? IDevtoolsFormat value) (-header value)
    (surrogate? value) (.-header value)
    :else (build-header value)))

(defn has-body-api-call [value]
  (cond
    (satisfies? IDevtoolsFormat value) (-has-body value)
    (surrogate? value) (.-hasBody value)
    :else false))                                           ; body is emulated using surrogate references

(defn body-api-call [value]
  (cond
    (satisfies? IDevtoolsFormat value) (-body value)
    (surrogate? value) (build-surrogate-body value)))