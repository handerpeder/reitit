(ns reitit.middleware-test
  (:require [clojure.test :refer [deftest testing is are]]
            [reitit.middleware :as middleware]
            [clojure.set :as set]
            [reitit.core :as reitit])
  #?(:clj
     (:import (clojure.lang ExceptionInfo))))

(deftest expand-middleware-test

  (testing "middleware records"

    (testing ":name is mandatory"
      (is (thrown-with-msg?
            ExceptionInfo
            #"Middleware must have :name defined"
            (middleware/create
              {:wrap identity
               :gen (constantly identity)}))))

    (testing ":wrap & :gen are exclusive"
      (is (thrown-with-msg?
            ExceptionInfo
            #"Middleware can't both :wrap and :gen defined"
            (middleware/create
              {:name ::test
               :wrap identity
               :gen (constantly identity)}))))

    (testing "middleware"
      (let [calls (atom 0)
            wrap (fn [handler value]
                   (swap! calls inc)
                   (fn [request]
                     [value request]))
            ->app (fn [ast handler]
                    (middleware/compile-handler
                      (middleware/expand ast :meta {})
                      handler))]

        (testing "as middleware function"
          (reset! calls 0)
          (let [app (->app [[#(wrap % :value)]] identity)]
            (dotimes [_ 10]
              (is (= [:value :request] (app :request)))
              (is (= 1 @calls)))))

        (testing "as middleware vector"
          (reset! calls 0)
          (let [app (->app [[wrap :value]] identity)]
            (dotimes [_ 10]
              (is (= [:value :request] (app :request)))
              (is (= 1 @calls)))))

        (testing "as map"
          (reset! calls 0)
          (let [app (->app [[{:wrap #(wrap % :value)}]] identity)]
            (dotimes [_ 10]
              (is (= [:value :request] (app :request)))
              (is (= 1 @calls)))))

        (testing "as map vector"
          (reset! calls 0)
          (let [app (->app [[{:wrap wrap} :value]] identity)]
            (dotimes [_ 10]
              (is (= [:value :request] (app :request)))
              (is (= 1 @calls)))))

        (testing "as Middleware"
          (reset! calls 0)
          (let [app (->app [[(middleware/create {:wrap #(wrap % :value)})]] identity)]
            (dotimes [_ 10]
              (is (= [:value :request] (app :request)))
              (is (= 1 @calls)))))

        (testing "as Middleware vector"
          (reset! calls 0)
          (let [app (->app [[(middleware/create {:wrap wrap}) :value]] identity)]
            (dotimes [_ 10]
              (is (= [:value :request] (app :request)))
              (is (= 1 @calls)))))))

    (testing "compiled Middleware"
      (let [calls (atom 0)
            mw {:gen (fn [meta _]
                       (swap! calls inc)
                       (fn [handler value]
                         (swap! calls inc)
                         (fn [request]
                           [meta value request])))}
            ->app (fn [ast handler]
                    (middleware/compile-handler
                      (middleware/expand ast :meta {})
                      handler))]

        (testing "as map"
          (reset! calls 0)
          (let [app (->app [[mw :value]] identity)]
            (dotimes [_ 10]
              (is (= [:meta :value :request] (app :request)))
              (is (= 2 @calls)))))

        (testing "as Middleware"
          (reset! calls 0)
          (let [app (->app [[(middleware/create mw) :value]] identity)]
            (dotimes [_ 10]
              (is (= [:meta :value :request] (app :request)))
              (is (= 2 @calls)))))

        (testing "nil unmounts the middleware"
          (let [app (->app [{:gen (constantly nil)}
                            {:gen (constantly nil)}] identity)]
            (dotimes [_ 10]
              (is (= :request (app :request))))))))))

(deftest middleware-handler-test

  (testing "all paths should have a handler"
    (is (thrown-with-msg?
          ExceptionInfo
          #"path \"/ping\" doesn't have a :handler defined"
          (middleware/router ["/ping"]))))

  (testing "middleware-handler"
    (let [mw (fn [handler value]
               (fn [request]
                 (conj (handler (conj request value)) value)))
          api-mw #(mw % :api)
          handler #(conj % :ok)
          router (middleware/router
                   [["/ping" handler]
                    ["/api" {:middleware [api-mw]}
                     ["/ping" handler]
                     ["/admin" {:middleware [[mw :admin]]}
                      ["/ping" handler]]]])
          ->app (fn [router]
                  (let [h (middleware/middleware-handler router)]
                    (fn [path]
                      (if-let [f (h path)]
                        (f [])))))
          app (->app router)]

      (testing "not found"
        (is (= nil (app "/favicon.ico"))))

      (testing "normal handler"
        (is (= [:ok] (app "/ping"))))

      (testing "with middleware"
        (is (= [:api :ok :api] (app "/api/ping"))))

      (testing "with nested middleware"
        (is (= [:api :admin :ok :admin :api] (app "/api/admin/ping"))))

      (testing ":gen middleware can be unmounted at creation-time"
        (let [mw1 {:name ::mw1, :gen (constantly #(mw % ::mw1))}
              mw2 {:name ::mw2, :gen (constantly nil)}
              mw3 {:name ::mw3, :wrap #(mw % ::mw3)}
              router (middleware/router
                       ["/api" {:name ::api
                                :middleware [mw1 mw2 mw3 mw2]
                                :handler handler}])
              app (->app router)]

          (is (= [::mw1 ::mw3 :ok ::mw3 ::mw1] (app "/api")))

          (testing "routes contain list of actually applied mw"
            (is (= [::mw1 ::mw3] (->> (reitit/routes router)
                                      first
                                      last
                                      :middleware
                                      (map :name)))))

          (testing "match contains list of actually applied mw"
            (is (= [::mw1 ::mw3] (->> "/api"
                                      (reitit/match-by-path router)
                                      :result
                                      :middleware
                                      (map :name))))))))))
