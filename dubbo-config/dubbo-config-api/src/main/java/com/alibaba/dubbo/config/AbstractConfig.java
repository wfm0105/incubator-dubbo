/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.config;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.CollectionUtils;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.support.Parameter;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility methods and public methods for parsing configuration
 * 翻译 ：主要提供配置解析与校验相关的工具方法
 *
 * @export
 */
public abstract class AbstractConfig implements Serializable {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractConfig.class);
    private static final long serialVersionUID = 4267533505537413570L;
    private static final int MAX_LENGTH = 200;

    private static final int MAX_PATH_LENGTH = 200;

    private static final Pattern PATTERN_NAME = Pattern.compile("[\\-._0-9a-zA-Z]+");

    private static final Pattern PATTERN_MULTI_NAME = Pattern.compile("[,\\-._0-9a-zA-Z]+");

    private static final Pattern PATTERN_METHOD_NAME = Pattern.compile("[a-zA-Z][0-9a-zA-Z]*");

    private static final Pattern PATTERN_PATH = Pattern.compile("[/\\-$._0-9a-zA-Z]+");

    private static final Pattern PATTERN_NAME_HAS_SYMBOL = Pattern.compile("[:*,/\\-._0-9a-zA-Z]+");

    private static final Pattern PATTERN_KEY = Pattern.compile("[*,\\-._0-9a-zA-Z]+");
    private static final Map<String, String> legacyProperties = new HashMap<String, String>();
    private static final String[] SUFFIXES = new String[]{"Config", "Bean"};

    //默认静态属性
    static {
        legacyProperties.put("dubbo.protocol.name", "dubbo.service.protocol");
        legacyProperties.put("dubbo.protocol.host", "dubbo.service.server.host");
        legacyProperties.put("dubbo.protocol.port", "dubbo.service.server.port");
        legacyProperties.put("dubbo.protocol.threads", "dubbo.service.max.thread.pool.size");
        legacyProperties.put("dubbo.consumer.timeout", "dubbo.service.invoke.timeout");
        legacyProperties.put("dubbo.consumer.retries", "dubbo.service.max.retry.providers");
        legacyProperties.put("dubbo.consumer.check", "dubbo.service.allow.no.provider");
        legacyProperties.put("dubbo.service.url", "dubbo.service.address");

        // this is only for compatibility
        Runtime.getRuntime().addShutdownHook(DubboShutdownHook.getDubboShutdownHook());
    }

    /*
    目前提供了四种配置方式：
   API 配置
   属性配置
   XML 配置
  注解配置
    PS:  id 属性，配置对象的编号，适用于除了 API 配置之外的三种配置方式，标记一个配置对象，可用于对象之间的引用。
      例如 XML 的 <dubbo:service provider="${PROVIDER_ID}"> ，其中 provider 为 <dubbo:provider> 的 ID 属性。
   **/
    protected String id;

    /**
     * 将键对应的值转换成目标的值。
     * <p>
     * 因为，新老配置可能有一些差异，通过该方法进行转换。
     *
     * @param key   键
     * @param value 值
     * @return 转换后的值
     */
    private static String convertLegacyValue(String key, String value) {
        if (value != null && value.length() > 0) {
            if ("dubbo.service.max.retry.providers".equals(key)) {
                return String.valueOf(Integer.parseInt(value) - 1);
            } else if ("dubbo.service.allow.no.provider".equals(key)) {
                return String.valueOf(!Boolean.parseBoolean(value));
            }
        }
        return value;
    }

    /**
     * 读取环境变量和 properties 配置到配置对象
     * <p>
     * 参见：<a href="https://dubbo.gitbooks.io/dubbo-user-book/configuration/properties.html">属性配置</a>
     *
     * @param config 配置对象
     */
    protected static void appendProperties(AbstractConfig config) {
        if (config == null) {
            return;
        }
        String prefix = "dubbo." + getTagName(config.getClass()) + ".";//初始化扩展属性前缀
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();
                if (name.length() > 3 && name.startsWith("set") && Modifier.isPublic(method.getModifiers())// 方法是 public 的 setting 方法。
                        && method.getParameterTypes().length == 1 && isPrimitive(method.getParameterTypes()[0])) {// 方法的唯一参数是基本数据类型
                    // 获得属性名，例如 `ApplicationConfig#setName(...)` 方法，对应的属性名为 name 。
                    String property = StringUtils.camelToSplitName(name.substring(3, 4).toLowerCase() + name.substring(4), ".");
                    // 【环境变量】优先从带有 `Config#id` 的配置中获取，例如：`dubbo.application.demo-provider.name` 。
                    String value = null;
                    if (config.getId() != null && config.getId().length() > 0) {
                        String pn = prefix + config.getId() + "." + property;
                        value = System.getProperty(pn);
                        if (!StringUtils.isBlank(value)) {
                            logger.info("Use System Property " + pn + " to config dubbo");
                        }
                    }
                    // 【环境变量】获取不到，其次不带 `Config#id` 的配置中获取，例如：`dubbo.application.name` 。
                    if (value == null || value.length() == 0) {
                        String pn = prefix + property;
                        value = System.getProperty(pn);
                        if (!StringUtils.isBlank(value)) {
                            logger.info("Use System Property " + pn + " to config dubbo");
                        }
                    }
                    if (value == null || value.length() == 0) {
                        // 覆盖优先级为：环境变量 > XML 配置 > properties 配置，因此需要使用 getter 判断 XML 是否已经设置
                        Method getter;
                        try {
                            getter = config.getClass().getMethod("get" + name.substring(3));
                        } catch (NoSuchMethodException e) {
                            try {
                                getter = config.getClass().getMethod("is" + name.substring(3));
                            } catch (NoSuchMethodException e2) {
                                getter = null;
                            }
                        }
                        if (getter != null) {
                            if (getter.invoke(config) == null) {// 使用 getter 判断 XML 是否已经设置
                                // 【properties 配置】优先从带有 `Config#id` 的配置中获取，例如：`dubbo.application.demo-provider.name` 。
                                if (config.getId() != null && config.getId().length() > 0) {
                                    value = ConfigUtils.getProperty(prefix + config.getId() + "." + property);
                                }
                                // 【properties 配置】获取不到，其次不带 `Config#id` 的配置中获取，例如：`dubbo.application.name` 。
                                if (value == null || value.length() == 0) {
                                    value = ConfigUtils.getProperty(prefix + property);
                                }
                                // 【properties 配置】老版本兼容，获取不到，最后不带 `Config#id` 的配置中获取，例如：`dubbo.protocol.name` 。
                                if (value == null || value.length() == 0) {
                                    String legacyKey = legacyProperties.get(prefix + property);
                                    if (legacyKey != null && legacyKey.length() > 0) {
                                        value = convertLegacyValue(legacyKey, ConfigUtils.getProperty(legacyKey));
                                    }
                                }

                            }
                        }
                    }
                    // 获取到值，进行反射设置。
                    if (value != null && value.length() > 0) {
                        method.invoke(config, convertPrimitive(method.getParameterTypes()[0], value));
                    }
                }
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    //获取类的后缀，Config 和Bean 结尾
    private static String getTagName(Class<?> cls) {
        String tag = cls.getSimpleName();
        for (String suffix : SUFFIXES) {
            if (tag.endsWith(suffix)) {
                tag = tag.substring(0, tag.length() - suffix.length());
                break;
            }
        }
        tag = tag.toLowerCase();
        return tag;
    }

    protected static void appendParameters(Map<String, String> parameters, Object config) {
        appendParameters(parameters, config, null);
    }

    @SuppressWarnings("unchecked")
    protected static void appendParameters(Map<String, String> parameters, Object config, String prefix) {
        if (config == null) {
            return;
        }
        Method[] methods = config.getClass().getMethods();//获取类上所有方法，为下面通过反射获得配置项的值做准备。
        for (Method method : methods) {
            try {
                String name = method.getName();//获取方法名称
                if ((name.startsWith("get") || name.startsWith("is"))//方法为获得基本类型 + public 的 getting 方法。（209-213行）
                        && !"getClass".equals(name)
                        && Modifier.isPublic(method.getModifiers())//判断方法是否是public
                        && method.getParameterTypes().length == 0//判断方法是否有返回值
                        && isPrimitive(method.getReturnType())) {//方法为获取基本类型，public 的 getting 方法。
                    Parameter parameter = method.getAnnotation(Parameter.class);//获取方法上的参数注解
                    if (method.getReturnType() == Object.class || parameter != null && parameter.excluded()) {//返回值类型为 Object 或排除( `@Parameter.exclue=true` )的配置项，跳过。
                        continue;
                    }
                    // 获得属性名
                    int i = name.startsWith("get") ? 3 : 2;
                    String prop = StringUtils.camelToSplitName(name.substring(i, i + 1).toLowerCase() + name.substring(i + 1), ".");
                    String key;
                    if (parameter != null && parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        key = prop;
                    }
                    // 获得属性值{@ path https://blog.csdn.net/sdta25196/article/details/73917784}
                    Object value = method.invoke(config);
                    String str = String.valueOf(value).trim();
                    if (value != null && str.length() > 0) {
                        if (parameter != null && parameter.escaped()) {
                            str = URL.encode(str);
                        }
                        if (parameter != null && parameter.append()) {
                            String pre = parameters.get(Constants.DEFAULT_KEY + "." + key); // default. 里获取，适用于 ServiceConfig =》ProviderConfig 、ReferenceConfig =》ConsumerConfig 。
                            if (pre != null && pre.length() > 0) {
                                str = pre + "," + str;
                            }
                            pre = parameters.get(key);// 通过 `parameters` 属性配置，例如 `AbstractMethodConfig.parameters` 。
                            if (pre != null && pre.length() > 0) {
                                str = pre + "," + str;
                            }
                        }
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }
                        parameters.put(key, str);//添加配置项到 parameters
                    } else if (parameter != null && parameter.required()) {//：当 `@Parameter.required = true` 时，校验配置项非空。
                        throw new IllegalStateException(config.getClass().getSimpleName() + "." + key + " == null");
                    }
                } else if ("getParameters".equals(name)//当方法为 #getParameters() 时
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && method.getReturnType() == Map.class) {
                    Map<String, String> map = (Map<String, String>) method.invoke(config, new Object[0]);//通过反射，获得 #getParameters() 的返回值为 map 。
                    if (map != null && map.size() > 0) {//：将 map 添加到 parameters ，kv 格式为 prefix:entry.key entry.value 。(因此，通过 #getParameters() 对应的属性，动态设置配置项，拓展出非 Dubbo 内置好的逻辑。)
                        String pre = (prefix != null && prefix.length() > 0 ? prefix + "." : "");
                        for (Map.Entry<String, String> entry : map.entrySet()) {
                            parameters.put(pre + entry.getKey().replace('-', '.'), entry.getValue());
                        }
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    protected static void appendAttributes(Map<Object, Object> parameters, Object config) {
        appendAttributes(parameters, config, null);
    }

    //将 @Parameter(attribute = true) 配置对象的属性，添加到参数集合
    protected static void appendAttributes(Map<Object, Object> parameters, Object config, String prefix) {
        if (config == null) {
            return;
        }
        Method[] methods = config.getClass().getMethods();
        for (Method method : methods) {
            try {
                String name = method.getName();
                if ((name.startsWith("get") || name.startsWith("is"))
                        && !"getClass".equals(name)
                        && Modifier.isPublic(method.getModifiers())
                        && method.getParameterTypes().length == 0
                        && isPrimitive(method.getReturnType())) {// 方法为获取基本类型，public 的 getting 方法。
                    Parameter parameter = method.getAnnotation(Parameter.class);
                    if (parameter == null || !parameter.attribute())
                        continue;
                    // 获得属性名
                    String key;
                    parameter.key();
                    if (parameter.key().length() > 0) {
                        key = parameter.key();
                    } else {
                        int i = name.startsWith("get") ? 3 : 2;
                        key = name.substring(i, i + 1).toLowerCase() + name.substring(i + 1);
                    }
                    // 获得属性值，存在则添加到 `parameters` 集合
                    Object value = method.invoke(config);
                    if (value != null) {
                        if (prefix != null && prefix.length() > 0) {
                            key = prefix + "." + key;
                        }
                        parameters.put(key, value);
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    //判断class 类型 如果时基本类型或者Objec 类型或者类型时私有的 则返回true
    private static boolean isPrimitive(Class<?> type) {
        return type.isPrimitive()
                || type == String.class
                || type == Character.class
                || type == Boolean.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == Object.class;
    }

    private static Object convertPrimitive(Class<?> type, String value) {
        if (type == char.class || type == Character.class) {
            return value.length() > 0 ? value.charAt(0) : '\0';
        } else if (type == boolean.class || type == Boolean.class) {
            return Boolean.valueOf(value);
        } else if (type == byte.class || type == Byte.class) {
            return Byte.valueOf(value);
        } else if (type == short.class || type == Short.class) {
            return Short.valueOf(value);
        } else if (type == int.class || type == Integer.class) {
            return Integer.valueOf(value);
        } else if (type == long.class || type == Long.class) {
            return Long.valueOf(value);
        } else if (type == float.class || type == Float.class) {
            return Float.valueOf(value);
        } else if (type == double.class || type == Double.class) {
            return Double.valueOf(value);
        }
        return value;
    }

    protected static void checkExtension(Class<?> type, String property, String value) {
        checkName(property, value);
        if (value != null && value.length() > 0
                && !ExtensionLoader.getExtensionLoader(type).hasExtension(value)) {
            throw new IllegalStateException("No such extension " + value + " for " + property + "/" + type.getName());
        }
    }

    protected static void checkMultiExtension(Class<?> type, String property, String value) {
        checkMultiName(property, value);
        if (value != null && value.length() > 0) {
            String[] values = value.split("\\s*[,]+\\s*");
            for (String v : values) {
                if (v.startsWith(Constants.REMOVE_VALUE_PREFIX)) {
                    v = v.substring(1);
                }
                if (Constants.DEFAULT_KEY.equals(v)) {
                    continue;
                }
                if (!ExtensionLoader.getExtensionLoader(type).hasExtension(v)) {
                    throw new IllegalStateException("No such extension " + v + " for " + property + "/" + type.getName());
                }
            }
        }
    }

    protected static void checkLength(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, null);
    }

    protected static void checkPathLength(String property, String value) {
        checkProperty(property, value, MAX_PATH_LENGTH, null);
    }

    protected static void checkName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_NAME);
    }

    protected static void checkNameHasSymbol(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_NAME_HAS_SYMBOL);
    }

    protected static void checkKey(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_KEY);
    }

    protected static void checkMultiName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_MULTI_NAME);
    }

    protected static void checkPathName(String property, String value) {
        checkProperty(property, value, MAX_PATH_LENGTH, PATTERN_PATH);
    }

    protected static void checkMethodName(String property, String value) {
        checkProperty(property, value, MAX_LENGTH, PATTERN_METHOD_NAME);
    }

    protected static void checkParameterName(Map<String, String> parameters) {
        if (parameters == null || parameters.size() == 0) {
            return;
        }
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            checkNameHasSymbol(entry.getKey(), entry.getValue());
        }
    }

    protected static void checkProperty(String property, String value, int maxlength, Pattern pattern) {
        if (value == null || value.length() == 0) {
            return;
        }
        if (value.length() > maxlength) {
            throw new IllegalStateException("Invalid " + property + "=\"" + value + "\" is longer than " + maxlength);
        }
        if (pattern != null) {
            Matcher matcher = pattern.matcher(value);
            if (!matcher.matches()) {
                throw new IllegalStateException("Invalid " + property + "=\"" + value + "\" contains illegal " +
                        "character, only digit, letter, '-', '_' or '.' is legal.");
            }
        }
    }

    @Parameter(excluded = true)
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    protected void appendAnnotation(Class<?> annotationClass, Object annotation) {
        Method[] methods = annotationClass.getMethods();
        for (Method method : methods) {
            if (method.getDeclaringClass() != Object.class
                    && method.getReturnType() != void.class
                    && method.getParameterTypes().length == 0
                    && Modifier.isPublic(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers())) {
                try {
                    String property = method.getName();
                    if ("interfaceClass".equals(property) || "interfaceName".equals(property)) {
                        property = "interface";
                    }
                    String setter = "set" + property.substring(0, 1).toUpperCase() + property.substring(1);
                    Object value = method.invoke(annotation);
                    if (value != null && !value.equals(method.getDefaultValue())) {
                        Class<?> parameterType = ReflectUtils.getBoxedClass(method.getReturnType());
                        if ("filter".equals(property) || "listener".equals(property)) {
                            parameterType = String.class;
                            value = StringUtils.join((String[]) value, ",");
                        } else if ("parameters".equals(property)) {
                            parameterType = Map.class;
                            value = CollectionUtils.toStringMap((String[]) value);
                        }
                        try {
                            Method setterMethod = getClass().getMethod(setter, parameterType);
                            setterMethod.invoke(this, value);
                        } catch (NoSuchMethodException e) {
                            // ignore
                        }
                    }
                } catch (Throwable e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public String toString() {
        try {
            StringBuilder buf = new StringBuilder();
            buf.append("<dubbo:");
            buf.append(getTagName(getClass()));
            Method[] methods = getClass().getMethods();
            for (Method method : methods) {
                try {
                    String name = method.getName();
                    if ((name.startsWith("get") || name.startsWith("is"))
                            && !"getClass".equals(name) && !"get".equals(name) && !"is".equals(name)
                            && Modifier.isPublic(method.getModifiers())
                            && method.getParameterTypes().length == 0
                            && isPrimitive(method.getReturnType())) {
                        int i = name.startsWith("get") ? 3 : 2;
                        String key = name.substring(i, i + 1).toLowerCase() + name.substring(i + 1);
                        Object value = method.invoke(this);
                        if (value != null) {
                            buf.append(" ");
                            buf.append(key);
                            buf.append("=\"");
                            buf.append(value);
                            buf.append("\"");
                        }
                    }
                } catch (Exception e) {
                    logger.warn(e.getMessage(), e);
                }
            }
            buf.append(" />");
            return buf.toString();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
            return super.toString();
        }
    }

}
