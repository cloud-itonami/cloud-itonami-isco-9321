(ns packingfulfillment.governor-test
  "Tests ONLY the public `governor/check` entry point -- never a
  private helper directly. An earlier actor in this fleet
  (cloud-itonami-isic-0710) had a governor test that called a private
  `governor/...-violations` fn by name, which is a compile error since
  it is not public -- `check` exercises every rule through its real
  call path, so that mistake cannot recur here."
  (:require [clojure.test :refer [deftest is testing]]
            [packingfulfillment.governor :as gov]
            [packingfulfillment.store :as store]))

(deftest test-unregistered-practitioner-is-hard-violation
  (testing "Unregistered practitioner is a hard, un-overridable violation"
    (let [test-store (store/create-store)
          request {:practitioner-id "unknown-practitioner"
                    :type :reorder-packaging-supplies}
          proposal {:op :reorder-packaging-supplies :effect :propose :confidence 0.9}
          verdict (gov/check request {} proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (false? (:ok? verdict)))
      (is (some #(= :no-practitioner (:rule %)) (:violations verdict))))))

(deftest test-non-propose-effect-is-hard-violation
  (testing "A proposal with a non-:propose effect (would-be direct actuation) is a hard violation"
    (let [test-store (-> (store/create-store)
                          (store/register-practitioner! "prac-001" {:name "Alex Chen"}))
          request {:practitioner-id "prac-001" :type :reorder-packaging-supplies}
          proposal {:op :reorder-packaging-supplies :effect :execute :confidence 0.9}
          verdict (gov/check request {} proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (some #(= :no-actuation (:rule %)) (:violations verdict))))))

(deftest test-operating-packing-equipment-is-forbidden
  (testing "Proposing to directly operate physical packing/conveyor/handling equipment is a permanent hard violation"
    (let [test-store (-> (store/create-store)
                          (store/register-practitioner! "prac-001" {:name "Alex Chen"}))
          request {:practitioner-id "prac-001" :type :operate-packing-equipment}
          proposal {:op :operate-packing-equipment :effect :propose :confidence 0.99}
          verdict (gov/check request {} proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (false? (:ok? verdict)))
      (is (some #(= :scope-boundary (:rule %)) (:violations verdict))))))

(deftest test-acting-as-carrier-of-record-is-forbidden
  (testing "Proposing to hold itself out as shipping carrier of record is a permanent hard violation"
    (let [test-store (-> (store/create-store)
                          (store/register-practitioner! "prac-001" {:name "Alex Chen"}))
          request {:practitioner-id "prac-001" :type :act-as-carrier-of-record}
          proposal {:op :act-as-carrier-of-record :effect :propose :confidence 0.99}
          verdict (gov/check request {} proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (some #(= :scope-boundary (:rule %)) (:violations verdict))))))

(deftest test-unknown-op-is-forbidden
  (testing "An :unknown operation (advisor could not map the request) is a hard violation"
    (let [test-store (-> (store/create-store)
                          (store/register-practitioner! "prac-001" {:name "Alex Chen"}))
          request {:practitioner-id "prac-001" :type :something-unsupported}
          proposal {:op :unknown :effect :propose :confidence 0.0}
          verdict (gov/check request {} proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (some #(= :scope-boundary (:rule %)) (:violations verdict))))))

(deftest test-missing-spec-basis-is-hard-violation
  (testing "A record-writing proposal with no cited order/manifest basis is a hard violation"
    (let [test-store (-> (store/create-store)
                          (store/register-practitioner! "prac-001" {:name "Alex Chen"}))
          request {:practitioner-id "prac-001" :type :log-order-pack :order-id "order-001"}
          proposal {:op :log-order-pack :effect :propose :confidence 0.9
                    :order-id "order-001" :item-count 12 :cites []}
          verdict (gov/check request {} proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (some #(= :no-spec-basis (:rule %)) (:violations verdict))))))

(deftest test-item-count-mismatch-is-hard-violation
  (testing "A log-order-pack proposal's self-reported item-count disagreeing with the order's independently recorded declared item-count never auto-commits"
    (let [test-store (-> (store/create-store)
                          (store/register-practitioner! "prac-001" {:name "Alex Chen"})
                          (store/register-order! "order-001" {:declared-item-count 12}))
          request {:practitioner-id "prac-001" :type :log-order-pack :order-id "order-001"}
          proposal {:op :log-order-pack :effect :propose :confidence 0.9
                    :order-id "order-001" :item-count 9 :cites ["order-001"]}
          verdict (gov/check request {} proposal test-store)]
      (is (true? (:hard? verdict)))
      (is (false? (:ok? verdict)))
      (is (some #(= :item-count-mismatch (:rule %)) (:violations verdict))))))

(deftest test-item-count-match-is-not-a-violation
  (testing "A log-order-pack proposal whose item-count matches the order's ground truth is clean"
    (let [test-store (-> (store/create-store)
                          (store/register-practitioner! "prac-001" {:name "Alex Chen"})
                          (store/register-order! "order-001" {:declared-item-count 12}))
          request {:practitioner-id "prac-001" :type :log-order-pack :order-id "order-001"}
          proposal {:op :log-order-pack :effect :propose :confidence 0.9
                    :order-id "order-001" :item-count 12 :cites ["order-001"]}
          verdict (gov/check request {} proposal test-store)]
      (is (false? (:hard? verdict)))
      (is (true? (:ok? verdict)))
      (is (empty? (:violations verdict))))))

(deftest test-valid-reorder-proposal-passes-governor
  (testing "A valid, non-order-scoped reorder proposal from a registered practitioner passes cleanly"
    (let [test-store (-> (store/create-store)
                          (store/register-practitioner! "prac-001" {:name "Alex Chen"}))
          request {:practitioner-id "prac-001" :type :reorder-packaging-supplies}
          proposal {:op :reorder-packaging-supplies :effect :propose :confidence 0.9
                    :supply-type :small-boxes :quantity 200}
          verdict (gov/check request {} proposal test-store)]
      (is (false? (:hard? verdict)))
      (is (true? (:ok? verdict)))
      (is (false? (:escalate? verdict))))))

(deftest test-low-confidence-escalates
  (testing "Confidence below the floor (0.6) triggers escalation, not a hard hold"
    (let [test-store (-> (store/create-store)
                          (store/register-practitioner! "prac-001" {:name "Alex Chen"}))
          request {:practitioner-id "prac-001" :type :reorder-packaging-supplies}
          proposal {:op :reorder-packaging-supplies :effect :propose :confidence 0.4
                    :supply-type :small-boxes :quantity 200}
          verdict (gov/check request {} proposal test-store)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict)))
      (is (false? (:ok? verdict))))))

(deftest test-safety-concern-always-escalates-even-with-high-confidence
  (testing "flag-safety-concern ALWAYS escalates, even at high confidence with an otherwise-clean proposal"
    (let [test-store (-> (store/create-store)
                          (store/register-practitioner! "prac-001" {:name "Alex Chen"}))
          request {:practitioner-id "prac-001" :type :flag-safety-concern :order-id "order-001"}
          proposal {:op :flag-safety-concern :effect :propose :confidence 0.99
                    :order-id "order-001" :concern-type :damaged-goods :detail "crushed box"}
          verdict (gov/check request {} proposal test-store)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict)))
      (is (false? (:ok? verdict))))))

(deftest test-safety-concern-escalates-even-without-a-citation
  (testing "flag-safety-concern is deliberately exempt from the spec-basis check -- a safety flag must reach a human even with no ready order citation"
    (let [test-store (-> (store/create-store)
                          (store/register-practitioner! "prac-001" {:name "Alex Chen"}))
          request {:practitioner-id "prac-001" :type :flag-safety-concern}
          proposal {:op :flag-safety-concern :effect :propose :confidence 0.9
                    :concern-type :workspace-hazard :detail "wet floor near packing station"}
          verdict (gov/check request {} proposal test-store)]
      (is (false? (:hard? verdict)))
      (is (true? (:escalate? verdict)))
      (is (empty? (:violations verdict))))))
