# cloud-itonami-isco-9321

Open Occupation Blueprint for **ISCO-08 9321**: Hand Packers.

This repository designs a forkable OSS business for an independent packing and fulfillment practice: a packing-station robot manages order fulfillment under a governor-gated actor, so the practice keeps its own fulfillment records instead of renting a closed fulfillment SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a packing-station robot performs item scanning, box packing and shipping-label printing under an actor that proposes
actions and an independent **Packing Fulfillment Governor** that gates them. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions (such as
shipment above the client's registered order-value ceiling) require human sign-off.

A live sample of the operator console (robotics safety console, shared template) is rendered in [docs/samples/operator-console.html](docs/samples/operator-console.html) — pure-data HTML output of `kotoba.robotics.ui`.

## Core Contract

```text
order manifest + packing spec + shipping requirements
        |
        v
Fulfillment Advisor -> Packing Fulfillment Governor -> fulfill order/ship, or human sign-off
        |
        v
robot actions (gated) + operating records + audit ledger
```

No automated advice can dispatch a robot action the governor refuses, suppress
an operating record, or disclose sensitive data without governor approval and
audit evidence.

## Capability layer

Resolves via [`kotoba-lang/occupation`](https://github.com/kotoba-lang/occupation)
(ISCO-08 `9321`). Required capabilities:

- :robotics
- :forms
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
