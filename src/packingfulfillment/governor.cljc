(ns packingfulfillment.governor
  "Packing Fulfillment Governor -- the independent compliance layer
  that earns the Packing Fulfillment Advisor the right to commit a
  record. Wired as its own `:govern` node downstream of `:advise` in
  `packingfulfillment.actor`'s StateGraph -- the Advisor has no notion
  of whether a practitioner is registered, whether an order's declared
  item-count actually matches what was packed, or whether a proposal
  is safe to record, so this MUST be a separate system able to
  *reject* a proposal (itonami actor pattern, per CLAUDE.md Actors
  section).

  `:itonami.blueprint/governor` is `:packing-fulfillment-governor`
  (blueprint.edn). This blueprint (ISCO-08 9321, Hand Packers) is a
  COORDINATION-ONLY actor for an INDEPENDENT hand-packing/fulfillment
  practitioner working for e-commerce sellers -- e.g. a small-scale/
  gig packing-and-fulfillment operator. It helps that practitioner log
  completed order-packs, record item quality/damage checks, and
  coordinate shipment handoffs to a carrier, on the practitioner's own
  behalf. It NEVER operates physical packing/conveyor/handling
  equipment itself ('policy, not control' -- the practitioner's own
  hands and equipment do the physical work) and NEVER holds itself out
  as the shipping carrier of record. Both are permanently forbidden
  operations, not merely escalated ones.

  `check` is a pure function of (request, context, proposal, store) ->
  verdict; it never mutates the store. The StateGraph's `:decide` node
  routes on the verdict:
    :hard? true                -> :hold  (irreversible, no write)
    :escalate? true            -> :request-approval (human sign-off)
    otherwise                  -> :commit

  HARD invariants (:hard? true, ALWAYS :hold, never overridable by a
  human approver):
    1. practitioner provenance -- the request's practitioner must be
       registered.
    2. no-actuation            -- proposal :effect must be :propose
       (the Advisor never dispatches equipment or a carrier itself).
    3. scope-boundary          -- `forbidden-ops` (operating physical
       packing/conveyor/handling equipment directly; acting as
       shipping carrier of record; any unrecognized/:unknown op) never
       proceed, regardless of confidence.
    4. spec-basis              -- proposals that write an auditable
       coordination record must cite the order/manifest they are
       grounded in, never invent one.
    5. item-count ground truth -- a `:log-order-pack` proposal's
       self-reported item-count must match the order's INDEPENDENTLY
       recorded declared item-count (ground truth, not the Advisor's
       self-report) -- a mismatch is never allowed to auto-commit.

  ESCALATION invariants (:escalate? true, :hard? false, ALWAYS human
  sign-off but recoverable -- a human CAN approve these, unlike HARD
  violations):
    6. safety/damage concern   -- `:flag-safety-concern` ALWAYS
       escalates, even when confidence is high and every other check
       is clean. Deliberately excluded from the spec-basis check (#4)
       above -- a safety flag must be able to reach a human even
       without a ready order citation.
    7. low confidence           (< `confidence-floor`)."
  (:require [packingfulfillment.store :as store]))

(def confidence-floor 0.6)

(def forbidden-ops
  "Permanently excluded operation categories -- out of scope entirely,
  no human override possible. This actor is coordination-only: it
  never operates physical packing/conveyor/handling equipment
  directly, and never holds itself out as the shipping carrier of
  record. `:unknown` catches any proposal the Advisor could not map to
  a supported operation."
  #{:operate-packing-equipment :act-as-carrier-of-record :unknown})

(def escalate-always
  "Operations that ALWAYS require human escalation, even when
  otherwise clean. Flagging a safety or damage concern is the cardinal
  escalation trigger for this actor."
  #{:flag-safety-concern})

(def spec-basis-required-ops
  "Operations that write an auditable coordination record must cite
  the order/manifest they are grounded in. `:flag-safety-concern` is
  deliberately excluded -- see namespace docstring."
  #{:log-order-pack :record-quality-check :coordinate-shipment-handoff})

(def item-count-tolerance
  "Item counts are hand-counted, discrete units -- not a probabilistic
  measurement like weight -- so an exact match against ground truth is
  required."
  0)

(defn- abs-diff
  "Portable absolute difference (no `Math/abs`/`js/Math.abs` platform
  split needed)."
  [a b]
  (max (- a b) (- b a)))

(defn- practitioner-provenance-violations
  [practitioner-record]
  (when (nil? practitioner-record)
    [{:rule :no-practitioner :detail "practitioner not registered"}]))

(defn- no-actuation-violations
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :no-actuation
      :detail "effect must be :propose only (no direct equipment/carrier actuation)"}]))

(defn- scope-boundary-violations
  [proposal]
  (when (contains? forbidden-ops (:op proposal))
    [{:rule :scope-boundary
      :detail "operation outside permitted scope (operating physical packing/conveyor/handling equipment directly, and acting as shipping carrier of record, are permanently forbidden)"}]))

(defn- spec-basis-violations
  [proposal]
  (when (and (contains? spec-basis-required-ops (:op proposal))
             (empty? (:cites proposal)))
    [{:rule :no-spec-basis
      :detail "record-writing proposal has no cited order/manifest basis"}]))

(defn- item-count-mismatch-violations
  [proposal order-record]
  (when (= :log-order-pack (:op proposal))
    (let [declared (:declared-item-count order-record)
          packed (:item-count proposal)]
      (when (and order-record (some? declared) (some? packed)
                 (> (abs-diff packed declared) item-count-tolerance))
        [{:rule :item-count-mismatch
          :detail (str "packed item-count (" packed ") does not match the order's "
                       "independently recorded declared item-count (" declared
                       ") -- ground truth, not the advisor's self-report, governs")}]))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `packingfulfillment.store/Store`. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool
  :escalate? bool}`."
  [request _context proposal store]
  (let [practitioner-record (store/practitioner store (:practitioner-id request))
        order-record (when (:order-id request) (store/order store (:order-id request)))
        hard (into []
                   (concat (practitioner-provenance-violations practitioner-record)
                           (no-actuation-violations proposal)
                           (scope-boundary-violations proposal)
                           (spec-basis-violations proposal)
                           (item-count-mismatch-violations proposal order-record)))
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        always-escalate? (contains? escalate-always (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not always-escalate?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? always-escalate?))}))
