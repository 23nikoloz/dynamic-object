package com.github.rschmitt.dynamicobject;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

import java.io.StringWriter;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

class DynamicObjectInvocationHandler<T extends DynamicObject<T>> implements InvocationHandler {
    private static final Object EMPTY_MAP = Clojure.read("{}");
    private static final IFn GET = Clojure.var("clojure.core", "get");
    private static final IFn CONTAINS_KEY = Clojure.var("clojure.core", "contains?");
    private static final IFn ASSOC = Clojure.var("clojure.core", "assoc");
    private static final IFn DISSOC = Clojure.var("clojure.core", "dissoc");
    private static final IFn META = Clojure.var("clojure.core", "meta");
    private static final IFn WITH_META = Clojure.var("clojure.core", "with-meta");
    private static final IFn PPRINT;

    static {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("clojure.pprint"));

        PPRINT = Clojure.var("clojure.pprint/pprint");
    }

    private final Object map;
    private final Class<T> type;
    private final Constructor<MethodHandles.Lookup> lookupConstructor;

    DynamicObjectInvocationHandler(Object map, Class<T> type, Constructor<MethodHandles.Lookup> lookupConstructor) {
        this.map = map;
        this.type = type;
        this.lookupConstructor = lookupConstructor;
    }

    private T assoc(String key, Object value) {
        Object keyword = Clojure.read(":" + key);
        if (value instanceof DynamicObject)
            value = ((DynamicObject) value).getMap();
        return DynamicObject.wrap(ASSOC.invoke(map, keyword, value), type);
    }

    private T assocEx(String key, Object value) {
        Object keyword = Clojure.read(":" + key);
        if ((boolean) CONTAINS_KEY.invoke(map, keyword)) {
            throw new RuntimeException("");
        }

        return assoc(key, value);
    }

    private T without(String key) {
        Object keyword = Clojure.read(":" + key);
        return DynamicObject.wrap(DISSOC.invoke(map, keyword), type);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String methodName = method.getName();

        if (isBuilderMethod(method, args)) {
            if (isMetadataBuilder(method))
                return assocMeta(methodName, args[0]);
            return assoc(methodName, args[0]);
        }

        if (method.isDefault())
            return invokeDefaultMethod(proxy, method, args);

        switch (methodName) {
            case "getMap":
                return map;
            case "getType":
                return type;
            case "assoc":
                return assoc((String) args[0], args[1]);
            case "assocEx":
                return assocEx((String) args[0], args[1]);
            case "dissoc":
                return without((String) args[0]);
            case "toString":
                return map.toString();
            case "hashCode":
                return map.hashCode();
            case "prettyPrint":
                PPRINT.invoke(map);
                return null;
            case "toFormattedString":
                Writer w = new StringWriter();
                PPRINT.invoke(map, w);
                return w.toString();
            case "equals":
                Object other = args[0];
                if (other instanceof DynamicObject)
                    return map.equals(((DynamicObject) other).getMap());
                else
                    return method.invoke(map, args);
            default:
                if (isMetadataGetter(method))
                    return getMetadataFor(methodName);
                return getValueFor(method);
        }
    }

    private Object assocMeta(String key, Object value) {
        Object meta = META.invoke(map);
        if (meta == null)
            meta = EMPTY_MAP;
        meta = ASSOC.invoke(meta, key, value);
        return DynamicObject.wrap(WITH_META.invoke(map, meta), type);
    }

    private boolean isBuilderMethod(Method method, Object[] args) {
        return method.getReturnType().equals(type) && method.getParameterCount() == 1;
    }

    private boolean isMetadataBuilder(Method method) {
        if (method.getParameterCount() != 1)
            return false;
        for (Annotation[] annotations : method.getParameterAnnotations())
            for (Annotation annotation : annotations)
                if (annotation.annotationType().equals(Meta.class))
                    return true;
        return false;
    }

    private Object getMetadataFor(String key) {
        Object meta = META.invoke(map);
        Object val = GET.invoke(meta, key);
        return val;
    }

    private boolean isMetadataGetter(Method method) {
        if (method.getParameterCount() != 0)
            return false;
        for (Annotation annotation : method.getAnnotations())
            if (annotation.annotationType().equals(Meta.class))
                return true;
        return false;
    }

    private Object invokeDefaultMethod(Object proxy, Method method, Object[] args) throws Throwable {
        Class<?> declaringClass = method.getDeclaringClass();
        int TRUSTED = -1;
        return lookupConstructor.newInstance(declaringClass, TRUSTED)
                .unreflectSpecial(method, declaringClass)
                .bindTo(proxy)
                .invokeWithArguments(args);
    }

    @SuppressWarnings("unchecked")
    private Object getValueFor(Method method) {
        String methodName = method.getName();
        Object keywordKey = Clojure.read(":" + methodName);
        Object val = GET.invoke(map, keywordKey);
        if (val == null)
            val = getNonDefaultValue(method);
        if (val == null)
            return null;
        Class<?> returnType = method.getReturnType();
        if (returnType.equals(int.class) || returnType.equals(Integer.class))
            return returnInt(val);
        if (returnType.equals(float.class) || returnType.equals(Float.class))
            return returnFloat(val);
        if (returnType.equals(short.class) || returnType.equals(Short.class))
            return returnShort(val);
        if (DynamicObject.class.isAssignableFrom(returnType)) {
            Class<T> dynamicObjectType = (Class<T>) returnType;
            Object keyword = Clojure.read(":" + methodName);
            return DynamicObject.wrap(GET.invoke(map, keyword), dynamicObjectType);
        }
        return val;
    }

    private float returnFloat(Object val) {
        if (val instanceof Float)
            return (Float) val;
        return ((Double) val).floatValue();
    }

    private int returnInt(Object val) {
        if (val instanceof Integer)
            return (Integer) val;
        else return ((Long) val).intValue();
    }

    private short returnShort(Object val) {
        if (val instanceof Short)
            return (Short) val;
        if (val instanceof Integer)
            return ((Integer) val).shortValue();
        else return ((Long) val).shortValue();
    }

    private Object getNonDefaultValue(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            if (annotation.annotationType().equals(Key.class)) {
                String key = ((Key) annotation).value();
                if (key.charAt(0) != ':')
                    key = ":" + key;
                return GET.invoke(map, Clojure.read(key));
            }
        }
        return null;
    }
}
