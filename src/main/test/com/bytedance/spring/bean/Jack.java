package com.bytedance.spring.bean;

import com.bytedance.spring.ioc.annotation.Autowired;
import com.bytedance.spring.ioc.annotation.Component;
import com.bytedance.spring.ioc.annotation.Qualifier;
import com.bytedance.spring.ioc.annotation.Value;

@Component
public class Jack {
    @Value("jack")
    private String name;

    @Value("15")
    private int age;

    @Autowired
    @Qualifier("bean")
    private Bean bean;

    @Override
    public String toString() {
        return "Jack{" +
                "name='" + name + '\'' +
                ", age=" + age + "}" ;
    }
}
