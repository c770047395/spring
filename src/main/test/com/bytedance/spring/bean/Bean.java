package com.bytedance.spring.bean;

import com.bytedance.spring.ioc.annotation.Autowired;
import com.bytedance.spring.ioc.annotation.Component;
import com.bytedance.spring.ioc.annotation.Value;

@Component
public class Bean {
    @Value("cp")
    private String name;

    @Value("12")
    private int age;

    @Autowired
    private Jack jack;

    @Override
    public String toString() {
        return "Bean{" +
                "name='" + name + '\'' +
                ", age=" + age + "}";
    }
}
