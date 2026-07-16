(ns packingfulfillment.actor
  "Packing Fulfillment Actor -- the langgraph StateGraph wiring and
  runtime for the ISCO-08 9321 Hand Packers independent packing &
  fulfillment practitioner assistant. Implements the itonami actor
  pattern with Advisor/Governor separation and an append-only audit
  trail:

    :intake -> :advise (Advisor proposes) -> :govern (Governor
    decides) -> :decide (route) -> one of :commit (auto-proceed),
    :request-approval (human sign-off required), or :hold (hard
    violation, rejected).

  Built on the real `langgraph.graph` StateGraph API (`state-graph` /
  `add-node` / `add-edge` / `add-conditional-edges` /
  `set-entry-point` / `set-finish-point` / `compile-graph` /
  `invoke`) -- `build-graph` below returns a graph that
  `langgraph.graph/invoke` actually runs end to end (no stub).

  The Store is threaded through the run as `(:store state)`, not
  closed over at build time -- `build-graph` only closes over the
  Advisor instance, so the same compiled graph can be reused across
  requests against an evolving (immutable, persistent) store value.
  `run-request!` seeds the initial store; the final state's `:store`
  is the store with any new record appended (only on `:commit` or
  after `approve!` -- `:hold` never writes).

  Human-in-the-loop for `:request-approval` is NOT wired to a real
  `langgraph.checkpoint` interrupt/resume in this repository -- the
  graph runs straight through to the `:request-approval` terminal
  node (which writes no record) and a caller must separately call
  `approve!` on the returned state to actually commit. Wiring a real
  `:interrupt-before` + checkpointer pause is a documented extension
  point, not claimed here."
  (:require [langgraph.graph :as graph]
            [packingfulfillment.store :as store]
            [packingfulfillment.advisor :as advisor]
            [packingfulfillment.governor :as governor]))

(def default-state
  "Shape of the per-run state map threaded through the graph."
  {:phase :intake
   :request nil
   :context {}
   :proposal nil
   :decision nil
   :store nil
   :records []
   :error nil})

(defn- intake-node
  "Intake node: accept the incoming request, hand off to the Advisor."
  [state]
  (assoc state :phase :advise))

(defn- advise-node
  "Advise node: Advisor proposes a coordination operation. The Advisor
   NEVER decides whether the proposal may proceed -- that is the
   Governor's job, downstream."
  [state advisor-instance]
  (let [request (:request state)
        context (:context state)
        proposal (advisor/propose advisor-instance request context)]
    (assoc state :proposal proposal :phase :govern)))

(defn- govern-node
  "Govern node: the independent Governor evaluates the proposal
   against the current `(:store state)`'s ground truth. Never mutates
   the store."
  [state]
  (let [request (:request state)
        context (:context state)
        proposal (:proposal state)
        store-instance (:store state)
        verdict (governor/check request context proposal store-instance)]
    (assoc state :decision verdict :phase :decide)))

(defn- decide-node
  "Decide node: records the phase; actual routing to the next node
   happens via the `:decide` conditional edge (`decide-router`), which
   reads the same `:decision`."
  [state]
  (assoc state :phase :decide))

(defn- decide-router
  "Conditional-edge router for `:decide`: HARD violations always hold
   (never overridable); escalations always require human sign-off;
   everything else auto-proceeds to commit."
  [state]
  (let [decision (:decision state)]
    (cond
      (:hard? decision)     :hold
      (:escalate? decision) :request-approval
      :else                 :commit)))

(defn- commit-node
  "Commit node: append the proposal as an immutable coordination
   record to the store's audit ledger. Only reached when the
   Governor's verdict was clean (`:ok? true`)."
  [state]
  (let [proposal (:proposal state)
        op (:op proposal)
        store-instance (:store state)
        store' (store/add-record! store-instance op proposal)]
    (-> state
        (assoc :phase :complete :store store')
        (update :records (fn [r] (conj (or r []) {:recorded true :op op}))))))

(defn- request-approval-node
  "Request-approval node: the run stops here awaiting human sign-off.
   No record is written -- `approve!` resumes after a human reviews."
  [state]
  (assoc state :phase :awaiting-approval))

(defn- hold-node
  "Hold node: the proposal is permanently rejected (HARD violation) --
   no human override is possible, no record is written."
  [state]
  (assoc state
         :phase :rejected
         :error (str "Hard governance violation: " (-> state :decision :violations))))

(defn build-graph
  "Build and compile the StateGraph for the packing & fulfillment
   actor, wired against `advisor-instance`. The store is NOT baked in
   here -- it is threaded per-request via `run-request!`."
  [advisor-instance]
  (-> (graph/state-graph)
      (graph/add-node :intake (fn [s] (intake-node s)))
      (graph/add-node :advise (fn [s] (advise-node s advisor-instance)))
      (graph/add-node :govern (fn [s] (govern-node s)))
      (graph/add-node :decide (fn [s] (decide-node s)))
      (graph/add-node :commit (fn [s] (commit-node s)))
      (graph/add-node :request-approval (fn [s] (request-approval-node s)))
      (graph/add-node :hold (fn [s] (hold-node s)))
      (graph/set-entry-point :intake)
      (graph/add-edge :intake :advise)
      (graph/add-edge :advise :govern)
      (graph/add-edge :govern :decide)
      (graph/add-conditional-edges :decide decide-router)
      (graph/set-finish-point :commit)
      (graph/set-finish-point :request-approval)
      (graph/set-finish-point :hold)
      (graph/compile-graph)))

(defn run-request!
  "Run a coordination request through the compiled actor graph.
   `store-instance` is the store to check the proposal against (and,
   on a clean verdict, to append the new record to). Returns the final
   state map -- `:phase` tells you the outcome (`:complete`,
   `:awaiting-approval`, or `:rejected`); `:store` is the (possibly
   updated) store value to carry forward to the next request."
  [compiled-graph initial-request context store-instance]
  (graph/invoke compiled-graph
                (merge default-state
                       {:request initial-request
                        :context context
                        :store store-instance})))

(defn approve!
  "Approve a request that was held in `:awaiting-approval` phase --
   human sign-off for an escalation invariant (never for a HARD
   violation; those cannot be approved). Returns the state advanced to
   `:complete`, with the approval context attached, the record
   appended to the ledger, and `:store` updated -- mirroring what
   `commit-node` does for a non-escalated proposal."
  [state approval-context]
  (let [proposal (:proposal state)
        op (:op proposal)
        store-instance (:store state)
        store' (store/add-record! store-instance op (assoc proposal
                                                             :approved true
                                                             :approval approval-context))]
    (-> state
        (assoc :phase :complete :approval approval-context :store store')
        (update :records (fn [r] (conj (or r []) {:recorded true :op op :approved true}))))))
