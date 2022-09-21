package io.mycat.entityjson;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * @title:
 * @package: com.jingninghui.dbsecure.example.utils
 * @description:
 * @author: HuangYW
 * @date:
 */
public class ReflectUtil {
    /**
     * 判断对象是否为空，
     * @throws IntrospectionException e
     * @param obj obj
     * @param ignoreProperties 忽略的属性
     * @return 如果get 方法的数量等于 属性为空的数量 返回true，否则false
     */
    public static boolean isNullObject(Object obj, String... ignoreProperties) throws IntrospectionException {
        if (obj != null) {
            Class<?> objClass = obj.getClass();
            BeanInfo beanInfo = Introspector.getBeanInfo(objClass);
            PropertyDescriptor[] propertyDescriptors = beanInfo.getPropertyDescriptors();

            List<String> ignoreList = (ignoreProperties != null ? Arrays.asList(ignoreProperties) : null);

            int count = 1; // 结果为空的属性数量 初始化为1 去除Object的getClass方法
            int propertyCount = propertyDescriptors.length; // 属性数量
            if (ignoreList != null) {
                propertyCount -= ignoreList.size();
            }

            for (PropertyDescriptor propertyDescriptor : propertyDescriptors) {
                Method readMethod = propertyDescriptor.getReadMethod();
                String name = propertyDescriptor.getName();
                if (readMethod != null && (ignoreList == null || !ignoreList.contains(name))) {
                    Class<?> returnType = readMethod.getReturnType();
                    String typeName = returnType.getSimpleName();
                    Object invoke = null;
                    try {
                        invoke = readMethod.invoke(obj);
                        if (invoke == null) {
                            count += 1;
                        } else {
                            switch (typeName) {
                                default:
                                case "String":
                                    if ("".equals(invoke.toString().trim())) {
                                        count += 1;
                                    }
                                    break;
                                case "Integer":
                                    if ((Integer) invoke <= 0) {
                                        count += 1;
                                    }
                                    break;
                                case "int":
                                    if ((int) invoke <= 0) {
                                        count += 1;
                                    }
                                    break;
                                case "double":
                                    if ((double) invoke <= 0.0d) {
                                        count += 1;
                                    }
                                    break;
                                case "Double":
                                    if ((Double) invoke <= 0.0D) {
                                        count += 1;
                                    }
                                    break;
                                case "float":
                                    if ((float) invoke <= 0.0f) {
                                        count += 1;
                                    }
                                    break;
                                case "Float":
                                    if ((Float) invoke <= 0.0F) {
                                        count += 1;
                                    }
                                    break;
                                case "Long":
                                    if ((Long) invoke <= 0L) {
                                        count += 1;
                                    }
                                    break;
                                case "long":
                                    if ((long) invoke <= 0L) {
                                        count += 1;
                                    }
                                    break;
                            }
                        }
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        e.printStackTrace();
                    }
                }
            }
            return propertyCount == count;
        }
        return true;
    }
}
