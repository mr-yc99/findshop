package com.dp.model.enums;

public enum VoucherStatuesEnum {
    PUT_ON("上架", 1),
    PUT_OFF("下架", 0),
    EXPIRATION("下架", 3);
    private final String statue;
    private final int value;

    VoucherStatuesEnum(String statues, int value) {
        this.statue = statues;
        this.value = value;
    }

    public String getStatues() {
        return statue;
    }

    public int getValue() {
        return value;
    }
}
