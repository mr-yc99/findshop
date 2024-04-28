package com.dp.model.enums;

public class EnumTest1 {



}

enum test1 {

    PUT_ON("上架", 1),
    PUT_OFF("下架", 0),

    EXPIRATION("下架", 3);

    private final String statues;

    private final int value;

    test1(String statues, int value) {
        this.statues = statues;
        this.value = value;
    }

    public String getStatues() {
        return statues;
    }

    public int getValue() {
        return value;
    }
}
