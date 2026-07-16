(ns packingfulfillment.store
  "Packing Fulfillment Store -- the append-only audit ledger and
  persistent state for the ISCO-08 9321 Hand Packers independent
  packing & fulfillment practitioner actor. Implements the Store
  protocol for practitioner identity and order-record verification,
  and for append-only coordination records (order-pack logs, quality
  checks, shipment handoffs, safety flags, supply reorders).

  Pure data / pure functions only -- `MemStore` is an in-memory,
  immutable implementation. No real I/O, no network, no equipment
  control.")

(defprotocol Store
  "Store protocol for packing & fulfillment actor state and audit
  ledger."
  (practitioner [store practitioner-id]
    "Retrieve a practitioner record by ID. Returns nil if not found.")
  (order [store order-id]
    "Retrieve an order record by ID. This is the INDEPENDENTLY
     recorded ground truth for that order (e.g. `:declared-item-count`
     sourced from the order manifest) -- it is registered separately
     from, and never written by, the Advisor's proposals. Returns nil
     if not found.")
  (register-practitioner! [store practitioner-id practitioner-data]
    "Register a practitioner (adds to store, returns updated store).")
  (register-order! [store order-id order-data]
    "Register an order's ground-truth record (adds to store, returns
     updated store).")
  (add-record! [store record-type record-data]
    "Append an immutable coordination record to the audit ledger.
     Returns the updated store.")
  (records [store]
    "Return all records in the audit ledger (immutable)."))

(defn- now-ms
  "Current time in epoch milliseconds, portable across Clojure and
  ClojureScript. Isolated to this single call site so the rest of the
  namespace stays free of host-clock calls."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (js/Date.now)))

(defrecord MemStore [practitioners orders ledger]
  Store
  (practitioner [this practitioner-id]
    (get practitioners practitioner-id))
  (order [this order-id]
    (get orders order-id))
  (register-practitioner! [this practitioner-id practitioner-data]
    (MemStore. (assoc practitioners practitioner-id practitioner-data) orders ledger))
  (register-order! [this order-id order-data]
    (MemStore. practitioners (assoc orders order-id order-data) ledger))
  (add-record! [this record-type record-data]
    (let [record (assoc record-data :type record-type :timestamp (now-ms))]
      (MemStore. practitioners orders (conj ledger record))))
  (records [this]
    ledger))

(defn create-store
  "Create a new in-memory store for packing & fulfillment records."
  []
  (MemStore. {} {} []))
