package com.bytedance.spring;

import com.bytedance.spring.bean.Jack;
import com.bytedance.spring.context.ApplicationContext;
import com.bytedance.spring.context.impl.DefaultApplicationContext;

public class DefaultApplicationContextTest {
    public static void main(String[] args) {
        try {
            ApplicationContext context = new DefaultApplicationContext("com.bytedance.spring");
            Jack jack = (Jack) context.getBean("jack");
            System.out.println(jack);
            System.out.println("done");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
