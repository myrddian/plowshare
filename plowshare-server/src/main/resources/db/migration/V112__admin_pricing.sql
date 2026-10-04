CREATE TABLE admin_pricing (
    billing_route TEXT NOT NULL,
    model TEXT NOT NULL,
    rate_card JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (billing_route, model)
);
CREATE TABLE admin_pricing_history (
    id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor TEXT NOT NULL,
    billing_route TEXT NOT NULL,
    model TEXT NOT NULL,
    version TEXT NOT NULL,
    rate_card JSONB NOT NULL
);
