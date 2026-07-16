(ns packingfulfillment.advisor-test
  (:require [clojure.test :refer [deftest is testing]]
            [packingfulfillment.advisor :as advisor]))

(deftest test-log-order-pack-proposal
  (testing "Mock advisor proposes a log-order-pack operation grounded in the order"
    (let [a (advisor/mock-advisor)
          request {:type :log-order-pack :order-id "order-001"
                    :item-count 12 :package-weight-grams 850
                    :package-dims-cm [30 20 15]}
          proposal (advisor/propose a request {})]
      (is (= :log-order-pack (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (= 12 (:item-count proposal)))
      (is (= 850 (:package-weight-grams proposal)))
      (is (= ["order-001"] (:cites proposal)))
      (is (pos? (:confidence proposal))))))

(deftest test-record-quality-check-proposal
  (testing "Mock advisor proposes a record-quality-check operation"
    (let [a (advisor/mock-advisor)
          request {:type :record-quality-check :order-id "order-001"
                    :item-id "item-42" :condition :damaged :notes "dented corner"}
          proposal (advisor/propose a request {})]
      (is (= :record-quality-check (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (= :damaged (:condition proposal)))
      (is (= "item-42" (:item-id proposal)))
      (is (= ["order-001"] (:cites proposal))))))

(deftest test-coordinate-shipment-handoff-proposal
  (testing "Mock advisor proposes a coordinate-shipment-handoff operation"
    (let [a (advisor/mock-advisor)
          request {:type :coordinate-shipment-handoff :order-id "order-001"
                    :carrier "regional-parcel-co" :handoff-window "14:00-16:00"}
          proposal (advisor/propose a request {})]
      (is (= :coordinate-shipment-handoff (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (= "regional-parcel-co" (:carrier proposal)))
      (is (= ["order-001"] (:cites proposal))))))

(deftest test-flag-safety-concern-proposal
  (testing "Mock advisor proposes a flag-safety-concern operation, high confidence"
    (let [a (advisor/mock-advisor)
          request {:type :flag-safety-concern :order-id "order-001"
                    :concern-type :damaged-goods :detail "crushed box on receipt"}
          proposal (advisor/propose a request {})]
      (is (= :flag-safety-concern (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (= :damaged-goods (:concern-type proposal)))
      (is (>= (:confidence proposal) 0.6)))))

(deftest test-reorder-packaging-supplies-proposal
  (testing "Mock advisor proposes a reorder-packaging-supplies operation"
    (let [a (advisor/mock-advisor)
          request {:type :reorder-packaging-supplies :supply-type :small-boxes :quantity 200}
          proposal (advisor/propose a request {})]
      (is (= :reorder-packaging-supplies (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (= :small-boxes (:supply-type proposal)))
      (is (= 200 (:quantity proposal))))))

(deftest test-unknown-request-type-falls-back-safely
  (testing "Unrecognized request type proposes :unknown with zero confidence, never invents an op"
    (let [a (advisor/mock-advisor)
          request {:type :something-unsupported}
          proposal (advisor/propose a request {})]
      (is (= :unknown (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (= 0.0 (:confidence proposal))))))

(deftest test-mock-advisor-never-proposes-a-non-propose-effect
  (testing "Every mock-advisor proposal, across all supported op types, has :effect :propose"
    (let [a (advisor/mock-advisor)
          requests [{:type :log-order-pack :order-id "o1" :item-count 5}
                     {:type :record-quality-check :order-id "o1" :item-id "i1" :condition :ok}
                     {:type :coordinate-shipment-handoff :order-id "o1" :carrier "c"}
                     {:type :flag-safety-concern :order-id "o1" :concern-type :x}
                     {:type :reorder-packaging-supplies :supply-type :tape :quantity 10}]]
      (doseq [request requests]
        (is (= :propose (:effect (advisor/propose a request {}))))))))

(deftest test-llm-advisor-placeholder-is-honest-about-not-being-wired
  (testing "llm-advisor placeholder always proposes :unknown / confidence 0.0 -- forces escalation, is not wired to a real model"
    (let [a (advisor/llm-advisor nil)
          proposal (advisor/propose a {:type :log-order-pack} {})]
      (is (= :unknown (:op proposal)))
      (is (= :propose (:effect proposal)))
      (is (= 0.0 (:confidence proposal))))))
