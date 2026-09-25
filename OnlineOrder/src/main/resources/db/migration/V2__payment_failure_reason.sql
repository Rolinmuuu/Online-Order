-- Why the processor declined a charge (e.g. card_declined, insufficient_funds), shown to the
-- customer so they can try another card. Additive and nullable: safe to apply while the
-- previous release is still serving traffic (docs/ARCHITECTURE.md, ADR 9).
ALTER TABLE payments ADD COLUMN failure_reason TEXT;
