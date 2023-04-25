package com.bytedance.spring.extension;

import com.bytedance.spring.context.impl.DefaultApplicationContext;
import com.bytedance.spring.ioc.bean.BeanDefinition;

import java.lang.reflect.Method;
import java.util.List;

public interface Extension {

    void doOperation0 (DefaultApplicationContext context) throws Exception;

    void doOperation1 (DefaultApplicationContext context) throws Exception;

    void doOperation2 (DefaultApplicationContext context) throws Exception;

    void doOperation3 (DefaultApplicationContext context) throws Exception;

    void doOperation4 (DefaultApplicationContext context) throws Exception;

    void doOperation5 (DefaultApplicationContext context, BeanDefinition beanDefinition) throws Exception;

    void doOperation6 (DefaultApplicationContext context, Object o) throws Exception;

    void doOperation7 (DefaultApplicationContext context, Object o) throws Exception;

    void doOperation8 (DefaultApplicationContext context, Object o) throws Exception;

    // v1.1 更新内容，此操作对应于@Configuration注解处理之后的操作
    void doOperation9 (DefaultApplicationContext context) throws Exception;

    // v1.2 更新内容，此操作在代理对象时候，操作四种方法（前置、后置、返回、抛异常）
    void doOperationWhenProxy (DefaultApplicationContext context, Method methodBeProxy,
                               List<Method> before, List<Object> beforeAspect,
                               List<Method> after, List<Object> afterAspect,
                               List<Method> afterThrowing, List<Object> throwingAspect,
                               List<Method> afterReturning, List<Object> returningAspect) throws Exception;
}
