package com.bytedance.spring.context.impl;

import com.bytedance.spring.annotation.Bean;
import com.bytedance.spring.annotation.Configuration;
import com.bytedance.spring.context.ApplicationContext;
import com.bytedance.spring.exception.DataConversionException;
import com.bytedance.spring.exception.DuplicateBeanClassException;
import com.bytedance.spring.exception.DuplicateBeanNameException;
import com.bytedance.spring.exception.NoSuchBeanException;
import com.bytedance.spring.extension.Extension;
import com.bytedance.spring.ioc.annotation.*;
import com.bytedance.spring.ioc.bean.BeanDefinition;
import com.bytedance.spring.ioc.tools.MyTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultApplicationContext implements ApplicationContext {

    // 一级缓存，存放的是最终的对象
    // 缓存ioc里面的对象，key：beanName, value：object
    private final Map<String, Object> iocByName = new ConcurrentHashMap<>(256);

    // 二级缓存，存放半成品对象
    private final Map<String, Object> earlyRealObjects = new ConcurrentHashMap<>(256);

    // 二级缓存，存放半成品的代理对象
    private final Map<String, Object> earlyProxyObjects = new ConcurrentHashMap<>(16);

    // 保存所有的beanDefinition
    private final Set<BeanDefinition> beanDefinitions = new HashSet<>(256);

    // 保存此ioc容器中所有对象的beanName和beanDefinition的对应关系
    private final Map<String, BeanDefinition> allBeansByName = new HashMap<>();

    // 保存此ioc容器中所有对象的beanType和beanDefinition的对应关系
    private final Map<Class<?>, BeanDefinition> allBeansByType = new HashMap<>();

    // 保存bean的type和name的对应关系，采用缓存的形式存在
    private final Map<Class<?>, Set<String>> beanTypeAndName = new HashMap<>();

    // 保存所有类和切它的切面方法的集合
    private final Map<Class<?>, Set<Method>> aspect = new HashMap<>();

    // 对外扩展接口实现类的对象
    private List<? extends Extension> extensions = new ArrayList<>();

    // property配置文件的位置
    private final String propertyFile;

    // 记录了所有注解对应所有标注了这个注解的类
    private Map<Class<?>, List<Class<?>>> annotationType2Clazz = new HashMap<>();


    // 标注了needBeProxyed中的注解的类需要被代理
    private List<Class<?>> needBeProxyed = new ArrayList<>();

    // 记录关键位置的日志
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 加载的时候就扫描并创建对象，没有配置文件需要加载
     * @param basePackages 需要被ioc管理的包
     */
    public DefaultApplicationContext(String... basePackages) throws Exception {
        this (null, basePackages);
    }

    /**
     * 加载的时候就扫描并创建对象，需要加载配置文件
     * @param basePackages 需要被ioc管理的包
     */
    public DefaultApplicationContext(String propertyFile, String[] basePackages) throws Exception {

        this.propertyFile = propertyFile;

        //遍历包，找到目标类(原材料)
        findBeanDefinitions(basePackages);
        //根据原材料创建bean
        createObject();
        //先将需要代理的对象进行动态代理
        proxyObject();
        //自动装载并将切面类中的方法横切目标方法并装入ioc容器中
        autowireObject();
        // 注入配置类
        addConfig();
        //容器初始化日志
        logger.info("IOC容器初始化完成");
    }

    private void findBeanDefinitions(String... basePackages) throws IllegalStateException, ClassNotFoundException, DuplicateBeanNameException {
        for (String basePackage : basePackages) {
            //1、获取包下的所有类
            Set<Class<?>> classes = MyTools.getClasses(basePackage);
            for (Class<?> clazz : classes) {
                //2、遍历这些类，找到添加了注解的类
                for (Annotation annotation : clazz.getAnnotations()) {
                    List<Class<?>> clazzList = annotationType2Clazz.getOrDefault(annotation.annotationType(), new ArrayList<>());
                    clazzList.add(clazz);
                    annotationType2Clazz.put(annotation.annotationType(), clazzList);
                }
                Component componentAnnotation = clazz.getAnnotation(Component.class);
                Repository repository = clazz.getAnnotation(Repository.class);
                Service service = clazz.getAnnotation(Service.class);
                Controller controller = clazz.getAnnotation(Controller.class);
                final Configuration configuration = clazz.getAnnotation(Configuration.class);
                String beanName = null;
                if (componentAnnotation != null)    beanName = componentAnnotation.value();
                if (repository != null)    beanName = repository.value();
                if (service != null)    beanName = service.value();
                if (controller != null)    beanName = controller.value();
                if (configuration != null)  beanName = configuration.value();
                if (beanName != null) {      //如果此类带了@Component、@Repository、@Service、@Controller注解之一
                    beanName = checkBeanName(beanName, clazz);
                    //3、将这些类封装成BeanDefinition，装载到集合中
                    Boolean lazy = clazz.getAnnotation(Lazy.class) != null;
                    boolean singleton = true;
                    Scope scope = clazz.getAnnotation(Scope.class);
                    if (scope != null) {
                        String value = scope.value();
                        if ("prototype".equals(value)) {        //指定为非单例模式321
                            singleton = false;
                        } else if (!"singleton".equals(value)) { //非法值
                            throw new IllegalStateException();
                        }
                    }
                    BeanDefinition beanDefinition = new BeanDefinition(beanName, clazz, lazy, singleton);
                    //确保对所有的beanDefinition都有记录
                    beanDefinitions.add(beanDefinition);
                    allBeansByName.put(beanName, beanDefinition);
                    allBeansByType.put(clazz, beanDefinition);
                }
            }
            logger.info("扫描package:[{}]完成",basePackage);
        }
    }

    private String checkBeanName (String beanName, Class<?> clazz) throws DuplicateBeanNameException {
        if ("".equals(beanName)) {    //没有添加beanName则默认是类的首字母小写
            //获取类名首字母小写
            String className = clazz.getName().replaceAll(clazz.getPackage().getName() + ".", "");
            beanName = className.substring(0, 1).toLowerCase() + className.substring(1);
        }
        if (allBeansByName.containsKey(beanName)) {
            throw new DuplicateBeanNameException(beanName);
        }
        return beanName;
    }

    /**
     * 对每个非懒加载且是单例模式bean创建对象
     */
    private void createObject() throws DataConversionException, DuplicateBeanNameException, NoSuchMethodException, IllegalAccessException, InstantiationException, InvocationTargetException {
        for (BeanDefinition beanDefinition : beanDefinitions) {
            if (!beanDefinition.getLazy() && beanDefinition.getSingleton()) {        //如果是懒加载模式则先不将其放到ioc容器中
                createObject(beanDefinition);
            }
        }
        logger.info("所有单例模式且非懒加载模式的bean实例化完成");
    }

    /**
     * 为bean创建对象，如果有@Value注解则需要为其赋值
     * 为了防止忘记加set方法的问题，所以summer摒弃了spring的选择性的set方法注入，而是全局采用直接对属性设置访问权限并直接赋值
     * 如果是单例模式的创建则在检查完beanName和beanClass的冲突无误后加入IOC容器中，如果非单例则直接返回
     * @param beanDefinition
     * @return
     */
    private Object createObject(BeanDefinition beanDefinition) throws DataConversionException, DuplicateBeanNameException, NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {
        Class<?> beanClass = beanDefinition.getBeanClass();
        String beanName = beanDefinition.getBeanName();
        Object object = beanClass.getConstructor().newInstance();
        //对对象的属性赋值
        Field[] fields = beanClass.getDeclaredFields(); //拿到所有的域
        for (Field field : fields) {
            //注入标记了@Value的值
            Value annotation = field.getAnnotation(Value.class);
            if (annotation != null) {
                String value = annotation.value();
                Object val = convertVal(value, field);
                field.setAccessible(true);
                field.set(object, val);
            }
        }
        if (beanDefinition.getSingleton()) {    //如果是单例模式则加入ioc容器中
            if (earlyRealObjects.containsKey(beanName)) {
                throw new DuplicateBeanNameException(beanName);
            }
            //加入二级缓存的realObj中
            earlyRealObjects.put(beanName, object);
        }
        return object;
    }


    /**
     * 将@Value注解中String类型的值转化为相应的值
     * @param value
     * @param field
     * @return
     */
    private Object convertVal(String value, Field field) throws DataConversionException {
        Object val;
        switch (field.getType().getName()) {
            case "int":
            case "java.lang.Integer":
                try {
                    val = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new DataConversionException(value, field.getType().getName());
                }
                break;
            case "java.lang.String":
                val = value;
                break;
            case "char":
                if (value.length() < 1)
                    throw new DataConversionException(value, field.getType().getName());
                val = value.charAt(0);
                break;
            case "long":
            case "java.lang.Long":
                try {
                    val = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new DataConversionException(value, field.getType().getName());
                }
                break;
            case "short":
            case "java.lang.Short":
                try {
                    val = Short.parseShort(value);
                } catch (NumberFormatException e) {
                    throw new DataConversionException(value, field.getType().getName());
                }
                break;
            case "double":
            case "java.lang.Double":
                try {
                    val = Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    throw new DataConversionException(value, field.getType().getName());
                }
                break;
            case "boolean":
            case "java.lang.Boolean":
                try {
                    val = Boolean.parseBoolean(value);
                } catch (NumberFormatException e) {
                    throw new DataConversionException(value, field.getType().getName());
                }
                break;
            case "float":
            case "java.lang.Float":
                try {
                    val = Float.parseFloat(value);
                } catch (NumberFormatException e) {
                    throw new DataConversionException(value, field.getType().getName());
                }
                break;
            default:
                throw new DataConversionException(value, field.getType().getName());
        }
        return val;
    }

    private void proxyObject(){

    }

    /**
     * 向二级缓存的对象中自动注入依赖，当所有依赖都注入完成后即可将对象加入一级缓存，成为一个完整的对象
     * @throws Exception
     */
    private void autowireObject() throws Exception {
        for (Map.Entry<String, Object> objectEntry : earlyRealObjects.entrySet()) {
            autowireObject(objectEntry.getValue());
        }
        logger.info("所有单例模式且非懒加载模式的bean初始化完成");
    }

    /**
     * 为对象里标注了@Autowired的域注入值
     * @param object
     */
    private void autowireObject (Object object) throws Exception {
        final Class<?> clazz = object.getClass();
        for (Field field : clazz.getDeclaredFields()) {
            final Autowired autowired = field.getAnnotation(Autowired.class);
            final Qualifier qualifier = field.getAnnotation(Qualifier.class);
            field.setAccessible(true);
            if (field.get(object) != null) {
                continue;
            }
            if (autowired != null) {            //这个对象还有域需要注入
                Object bean;
                if (qualifier != null) {
                    //根据beanName进行注入
                    bean = getObject(qualifier.value());
                } else {
                    //根据beanType进行注入
                    bean = getObject(field.getType());
                }
                field.set(object, bean);
            }
        }
        // 检查此对象是否是单例、非懒加载的，如果是就将其加入一级缓存中，并从二级缓存中删除
        final BeanDefinition beanDefinition = allBeansByType.get(clazz);
        if (beanDefinition.getSingleton()) {
            String beanName = beanDefinition.getBeanName();
            iocByName.put(beanName, getObject(beanName));
            earlyRealObjects.remove(beanName);
            earlyProxyObjects.remove(beanName);
        }
    }

    /**
     * ioc容器构造过程中获取某个bean,可能是二级缓存中的demo
     * @param beanName
     * @return
     */
    private Object getObject(String beanName) throws Exception {
        final Object o = iocByName.get(beanName);       //从一级缓存中获取
        if ( o != null) {
            return o;
        }
        if (earlyProxyObjects.containsKey(beanName)) {  //从二级缓存中获取
            return earlyProxyObjects.get(beanName);
        }
        if (earlyRealObjects.containsKey(beanName)) {
            return earlyRealObjects.get(beanName);
        }
        //如果缓存中都没有则表示该bean为非单例或者懒加载的，则为其创建一个对象，并根据是否为单例而决定是否加入ioc容器
        final BeanDefinition beanDefinition = allBeansByName.get(beanName);
        if (beanDefinition == null)         //这个类并没有被ioc容器管理，可以考虑抛出异常提示用户
            return null;
        final Object bean = createBean(beanDefinition);
        if (beanDefinition.getSingleton()) {
            return iocByName.get(beanName);
        }
        return bean;
    }

    private Object getObject(Class<?> beanType) throws Exception {
        return getObject(getNameByType(beanType));
    }

    /**
     * 通过beanType获取beanName，调用此方法必须保证此beanType对应的是唯一的一个beanName
     * 如果此beanType在容器中还有对应派生类的对象、或者此beanType是接口类型，在容器中有多个实现类对象则会抛出DuplicateBeanClassException异常
     * @param beanType
     * @return
     * @throws DuplicateBeanClassException
     * @throws NoSuchBeanException
     */
    private String getNameByType(Class<?> beanType) throws DuplicateBeanClassException, NoSuchBeanException {
        final Set<String> namesByType = getNamesByType(beanType);
        if (namesByType.size() == 1) {
            return namesByType.iterator().next();
        } else if (namesByType.size() > 1) {
            throw new DuplicateBeanClassException(beanType);
        } else {
            throw new NoSuchBeanException();
        }
    }

    /**
     * 通过beanType获取beanName
     * 此方法可以获取此类型对应的所有派生类或者实现类（对于接口而言）的对象的beanName
     * @param beanType
     * @return
     */
    private Set<String> getNamesByType (Class<?> beanType) {
        if (beanTypeAndName.containsKey(beanType)) {
            return beanTypeAndName.get(beanType);
        } else {
            // 缓存中没有 so检查后加入缓存中
            Set<String> set = new HashSet<>();
            for (Map.Entry<String, BeanDefinition> entry : allBeansByName.entrySet()) {
                //如果beanType是当前entry.getKey()的父类或者实现的接口或者父接口或者本身
                if (beanType.isAssignableFrom(entry.getValue().getBeanClass())) {
                    set.add(entry.getKey());
                }
            }
            beanTypeAndName.put(beanType, set);
            return set;
        }
    }


    /**
     * 对于非单例或者延迟加载的bean在此创建实例化、代理、初始化
     * @param beanDefinition
     * @return
     */
    private Object createBean(BeanDefinition beanDefinition) throws Exception {

        //实例化一个对象，但并未初始化
        final Object object = createObject(beanDefinition);

        //TODO 对刚刚实例化的对象进行代理处理，需要先判断是否需要代理

        //对代理后的对象（如果需要）进行注入工作
        autowireObject(object);

        return object;
    }

    /**
     * 将配置类加入到容器
     * 对一级缓存做检查，如果是配置类，则需要执行其中标注了@Bean的方法，然后将方法的返回结果加入一级缓存
     */
    private void addConfig () throws DuplicateBeanNameException, InvocationTargetException, IllegalAccessException {
        final Set<Map.Entry<String, Object>> entrySet = iocByName.entrySet();
        for (Map.Entry<String, Object> objectEntry : entrySet) {
            final Object o = checkConfig(objectEntry.getValue());
            if (o != null) {
                iocByName.put(objectEntry.getKey(), o);
            }
        }
    }

    /**
     * 检查是否是配置类，如果是就执行其中的标注了@Bean的方法，并将返回结果加入一级缓存
     * 如果配置需要代理，则将代理类以同样的beanName加入一级缓存覆盖掉之前的原对象
     * @param obj
     * @return
     */
    private Object checkConfig (Object obj) throws DuplicateBeanNameException, InvocationTargetException, IllegalAccessException {
        final Class<?> clazz = obj.getClass();
        final Configuration configuration = clazz.getAnnotation(Configuration.class);
        if (configuration != null) {
            for (Method method : clazz.getDeclaredMethods()) {
                final Bean bean = method.getAnnotation(Bean.class);
                if (bean != null) {
                    // 执行这个方法， 并将执行后的结果加入到IOC容器中
                    Class<?> aClass = method.getReturnType();
                    String beanName = bean.name();
                    if ("".equals(beanName)) {
                        beanName = method.getName();
                    }
                    // 也许只要一个allBeansByName来判断就足够了，但由于是||，就算iocByName不是必要的也不会影响效率
                    if (allBeansByName.containsKey(beanName) || iocByName.containsKey(beanName)) {
                        throw new DuplicateBeanNameException(beanName);
                    }
                    final Object[] args = new Object[]{};
                    final Object result = method.invoke(obj, args);
                    iocByName.put(beanName, result);
                    BeanDefinition beanDefinition = new BeanDefinition(beanName, aClass, false, true);
                    allBeansByName.put(beanName, beanDefinition);
                }
            }

            // 如果需要代理则将此标注了@Configuration的类的代理类加入ioc容器
//            if (configuration.proxyBeanMethods())
//                return setConfigProxy(obj);
        }
        return null;
    }


    @Override
    public Object getBean(String beanName) throws Exception {
        return null;
    }

    @Override
    public <T> T getBean(Class<T> beanType) throws Exception {
        return null;
    }

    @Override
    public <T> T getBean(String name, Class<T> beanType) throws Exception {
        return null;
    }

    @Override
    public Class<?> getType(String name) throws NoSuchBeanException {
        return null;
    }

    @Override
    public <T> Map<String, T> getBeansOfType(Class<T> beanType) throws Exception {
        return null;
    }

    @Override
    public int getBeanDefinitionCount() {
        return 0;
    }

    @Override
    public String[] getBeanDefinitionNames() {
        return new String[0];
    }

    @Override
    public boolean containsBean(String name) {
        return false;
    }

    @Override
    public boolean containsBeanDefinition(String beanName) {
        return false;
    }

    @Override
    public BeanDefinition getBeanDefinition(String beanName) throws NoSuchBeanException {
        return null;
    }

    @Override
    public BeanDefinition getBeanDefinition(String beanName, Class<?> beanType) throws NoSuchBeanException {
        return null;
    }

    @Override
    public BeanDefinition getBeanDefinition(Class<?> beanType) throws DuplicateBeanClassException, NoSuchBeanException {
        return null;
    }
}
