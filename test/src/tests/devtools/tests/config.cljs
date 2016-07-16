(ns devtools.tests.config
  (:require [cljs.test :refer-macros [deftest testing is]]
            [devtools.prefs :refer [merge-prefs! set-pref! set-prefs! update-pref! get-prefs pref]]
            [devtools.core :refer [installed?]]))

(deftest test-config
  (testing "expected config overrides passed via compiler options are present"                                                ; see project.clj :tests-with-config profile >
    (is (= (pref :features-to-install) [:hints]))
    (is (= (installed? :hints) true))
    (is (= (installed? :formatters) false))
    (is (= (pref :fn-symbol) "F"))
    (is (= (pref :print-config-overrides) true))))
