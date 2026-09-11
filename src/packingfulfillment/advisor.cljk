(ns packingfulfillment.advisor
  "Packing Fulfillment Advisor -- the proposal layer for the ISCO-08
  9321 Hand Packers independent packing & fulfillment practitioner
  actor. Proposes coordination operations grounded in a hand-packing/
  fulfillment practitioner's actual working day -- order-pack logging,
  item quality/damage checks, carrier shipment handoffs, safety/
  quality concern flags, and packaging-supply reorder requests --
  based on requests, but NEVER commits records itself, NEVER operates
  physical packing/conveyor/handling equipment, and NEVER makes
  governance decisions. Those are the Store's and the Governor's jobs,
  respectively.

  This actor is scoped to an INDEPENDENT practitioner's own paperwork
  (e.g. a small-scale/gig packing-and-fulfillment operator working for
  e-commerce sellers) -- it is a coordination/logging tool, not a
  dispatcher for a fulfillment robot and not the shipping carrier of
  record. See `packingfulfillment.governor` for the enforced scope
  boundary.")

(defprotocol Advisor
  "Advisor protocol for proposing packing & fulfillment coordination
  operations."
  (propose [advisor request context]
    "Propose a coordination operation from a request. Returns a
     proposal map with :op, :effect (always :propose), :confidence,
     and supporting data."))

(defn mock-advisor
  "Default deterministic advisor that proposes standard packing &
  fulfillment coordination operations based on request `:type`. Always
  returns :propose effect. This is a MOCK -- it performs no real
  reasoning and calls no LLM; it exists so the actor graph and
  Governor can be tested and demonstrated deterministically.

  Supported request types:
    :log-order-pack             -- log a completed order-pack (item
                                    count, package weight/dims).
    :record-quality-check       -- record a quality/damage check on an
                                    item before packing.
    :coordinate-shipment-handoff -- coordinate a shipment handoff of a
                                    packed order to a carrier.
    :flag-safety-concern        -- flag a safety or quality concern.
                                    ALWAYS escalates in the Governor,
                                    regardless of confidence -- see
                                    `packingfulfillment.governor`.
    :reorder-packaging-supplies -- log a low-stock packaging-supply
                                    reorder request (boxes, tape,
                                    void-fill).
  Any other `:type` proposes `:unknown` with confidence 0.0, which the
  Governor's forbidden-ops check permanently blocks -- there is no
  operation this actor supports outside the list above."
  []
  (reify Advisor
    (propose [this request _context]
      (let [req-type (:type request)
            order-id (:order-id request)
            cites (if order-id [order-id] [])
            op (case req-type
                 :log-order-pack
                 {:op :log-order-pack
                  :confidence 0.85
                  :order-id order-id
                  :item-count (:item-count request)
                  :package-weight-grams (:package-weight-grams request)
                  :package-dims-cm (:package-dims-cm request)
                  :cites cites}

                 :record-quality-check
                 {:op :record-quality-check
                  :confidence 0.85
                  :order-id order-id
                  :item-id (:item-id request)
                  :condition (:condition request)
                  :notes (:notes request)
                  :cites cites}

                 :coordinate-shipment-handoff
                 {:op :coordinate-shipment-handoff
                  :confidence 0.8
                  :order-id order-id
                  :carrier (:carrier request)
                  :handoff-window (:handoff-window request)
                  :cites cites}

                 :flag-safety-concern
                 {:op :flag-safety-concern
                  :confidence 0.9
                  :order-id order-id
                  :concern-type (:concern-type request)
                  :detail (:detail request)}

                 :reorder-packaging-supplies
                 {:op :reorder-packaging-supplies
                  :confidence 0.75
                  :supply-type (:supply-type request)
                  :quantity (:quantity request)}

                 {:op :unknown :confidence 0.0})]
        (assoc op :effect :propose)))))

(defn llm-advisor
  "Advisor backed by an LLM (ChatModel). Always returns :propose
  effect; LLM parse failures yield confidence 0.0 (forces escalation).
  NOT wired to a real model in this repository -- this is an honest
  placeholder, not a real integration. See the `build-actor` skill /
  CLAUDE.md for the swap-in pattern (a real `langchain.model`
  chat-model instance)."
  [chat-model]
  (reify Advisor
    (propose [this request context]
      ;; Placeholder: a real implementation would call chat-model and
      ;; parse its response to extract :op, :confidence, etc. On any
      ;; error, return confidence 0.0 so the Governor forces
      ;; escalation rather than silently proceeding.
      {:op :unknown :effect :propose :confidence 0.0})))
