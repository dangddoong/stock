CREATE TABLE product (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_code VARCHAR(50)  NOT NULL,
    name         VARCHAR(200) NOT NULL,
    quantity     BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uk_product_code UNIQUE (product_code),
    CONSTRAINT ck_product_quantity CHECK (quantity >= 0)
);
