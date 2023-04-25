package com.bytedance.spring.exception;

public class DataConversionException extends Exception {

    private final Object val1;
    private final Object val2;

    public DataConversionException(Object val1, Object val2) {
        System.err.println("发生数据转化异常：" + val1 + "转化到" + val2);
        this.val1 = val1;
        this.val2 = val2;
    }

    @Override
    public void printStackTrace() {
        System.err.println("发生数据转化异常：" + val1 + "转化到" + val2);
    }
}
