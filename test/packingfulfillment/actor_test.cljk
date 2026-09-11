(ns packingfulfillment.actor-test
  "Exercises the real, compiled `langgraph.graph` StateGraph end to
  end -- not just the individual Advisor/Governor/Store units. Only
  calls public functions (`actor/build-graph`, `actor/run-request!`,
  `actor/approve!`, `store/*`, `advisor/*`)."
  (:require [clojure.test :refer [deftest is testing]]
            [packingfulfillment.actor :as actor]
            [packingfulfillment.advisor :as advisor]
            [packingfulfillment.store :as store]))

(deftest test-clean-proposal-auto-commits-end-to-end
  (testing "A clean, high-confidence, non-escalating proposal runs the full graph to :complete and appends a ledger record"
    (let [st (-> (store/create-store)
                 (store/register-practitioner! "prac-001" {:name "Alex Chen"}))
          g (actor/build-graph (advisor/mock-advisor))
          result (actor/run-request! g
                                      {:type :reorder-packaging-supplies
                                       :practitioner-id "prac-001"
                                       :supply-type :small-boxes :quantity 200}
                                      {}
                                      st)]
      (is (= :complete (:phase result)))
      (is (= 1 (count (:records result))))
      (is (= :reorder-packaging-supplies (:op (first (:records result)))))
      (is (= 1 (count (store/records (:store result))))))))

(deftest test-hard-violation-holds-end-to-end
  (testing "An unregistered practitioner's proposal is held (hard violation) -- never committed, no ledger write"
    (let [st (store/create-store)
          g (actor/build-graph (advisor/mock-advisor))
          result (actor/run-request! g
                                      {:type :reorder-packaging-supplies
                                       :practitioner-id "unknown-prac"
                                       :supply-type :small-boxes :quantity 200}
                                      {}
                                      st)]
      (is (= :rejected (:phase result)))
      (is (empty? (:records result)))
      (is (some? (:error result)))
      (is (= 0 (count (store/records (:store result))))))))

(deftest test-forbidden-op-holds-end-to-end
  (testing "A proposal to operate physical packing equipment is held -- forbidden ops never reach commit"
    (let [st (-> (store/create-store)
                 (store/register-practitioner! "prac-001" {:name "Alex Chen"}))
          g (actor/build-graph (advisor/mock-advisor))
          result (actor/run-request! g
                                      {:type :operate-packing-equipment
                                       :practitioner-id "prac-001"}
                                      {}
                                      st)]
      (is (= :rejected (:phase result)))
      (is (empty? (:records result))))))

(deftest test-safety-concern-escalates-end-to-end
  (testing "A safety-concern flag always routes to human approval, never auto-commits"
    (let [st (-> (store/create-store)
                 (store/register-practitioner! "prac-001" {:name "Alex Chen"}))
          g (actor/build-graph (advisor/mock-advisor))
          result (actor/run-request! g
                                      {:type :flag-safety-concern
                                       :practitioner-id "prac-001"
                                       :order-id "order-001"
                                       :concern-type :damaged-goods
                                       :detail "crushed box"}
                                      {}
                                      st)]
      (is (= :awaiting-approval (:phase result)))
      (is (empty? (:records result)))
      (is (= 0 (count (store/records (:store result))))))))

(deftest test-approve-after-escalation-commits-end-to-end
  (testing "approve! advances an escalated, held-for-review state to :complete and appends the ledger record"
    (let [st (-> (store/create-store)
                 (store/register-practitioner! "prac-001" {:name "Alex Chen"}))
          g (actor/build-graph (advisor/mock-advisor))
          held (actor/run-request! g
                                    {:type :flag-safety-concern
                                     :practitioner-id "prac-001"
                                     :order-id "order-001"
                                     :concern-type :damaged-goods
                                     :detail "crushed box"}
                                    {}
                                    st)
          approved (actor/approve! held {:approver "shift-lead"})]
      (is (= :complete (:phase approved)))
      (is (= 1 (count (:records approved))))
      (is (true? (:approved (first (:records approved)))))
      (is (= 1 (count (store/records (:store approved))))))))

(deftest test-item-count-mismatch-holds-end-to-end
  (testing "A log-order-pack proposal whose self-reported item-count disagrees with the order's independently recorded declared item-count is held"
    (let [st (-> (store/create-store)
                 (store/register-practitioner! "prac-001" {:name "Alex Chen"})
                 (store/register-order! "order-001" {:declared-item-count 12}))
          g (actor/build-graph (advisor/mock-advisor))
          result (actor/run-request! g
                                      {:type :log-order-pack
                                       :practitioner-id "prac-001"
                                       :order-id "order-001"
                                       :item-count 9
                                       :package-weight-grams 800}
                                      {}
                                      st)]
      (is (= :rejected (:phase result)))
      (is (empty? (:records result))))))

(deftest test-item-count-match-commits-end-to-end
  (testing "A log-order-pack proposal whose item-count matches the order's ground truth auto-commits"
    (let [st (-> (store/create-store)
                 (store/register-practitioner! "prac-001" {:name "Alex Chen"})
                 (store/register-order! "order-001" {:declared-item-count 12}))
          g (actor/build-graph (advisor/mock-advisor))
          result (actor/run-request! g
                                      {:type :log-order-pack
                                       :practitioner-id "prac-001"
                                       :order-id "order-001"
                                       :item-count 12
                                       :package-weight-grams 800}
                                      {}
                                      st)]
      (is (= :complete (:phase result)))
      (is (= 1 (count (:records result))))
      (is (= 12 (:item-count (first (store/records (:store result)))))))))
